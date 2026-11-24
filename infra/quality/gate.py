"""Run the local and CI quality gates from the repository root."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import unquote
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TEST_FIXTURES = ROOT / "infra/security/test-fixtures"
MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
REACTOR = [ROOT, *(ROOT / module.text for module in ET.parse(ROOT / "pom.xml")
                   .findall("m:modules/m:module", MAVEN_NS))]
QUICK_EXCLUSIONS = (
    "*IntegrationTests", "ShopUserApplicationTests", "ShopProductApplicationTests",
    "GatewayAuthenticationTests", "GatewayAntiAbuseTests",
)
DOC_DIRS = (
    "agent-engineering", "architecture", "standards", "database", "middleware",
    "messaging", "security", "business", "decisions",
)


def run(*command, env=None, capture=False):
    return subprocess.run(command, cwd=ROOT, env=env, check=True, text=True,
                          encoding="utf-8", stdout=subprocess.PIPE if capture else None).stdout


def lint(base):
    run("git", "diff", "--check")
    run("git", "diff", "--cached", "--check")
    if base:
        run("git", "diff", "--check", base, "HEAD", "--")
    tracked = run("git", "-c", "core.quotepath=false", "ls-files", "-z", capture=True).split("\0")
    for name in tracked:
        if name.startswith("infra/local-secrets/") and name != "infra/local-secrets/.gitignore":
            raise ValueError(f"Local secret path in Git index: {name}")
    for module in REACTOR:
        ET.parse(module / "pom.xml")
        for mapper in (module / "src/main/resources/mapper").glob("*.xml"):
            ET.parse(mapper)
    docs = ROOT / "docs"
    markdown = [ROOT / name for name in ("AGENTS.md", "README.md", "PROJECT_STATUS.md", "SESSION_HANDOFF.md")]
    markdown.append(docs / "README.md")
    for folder in DOC_DIRS:
        markdown.extend((docs / folder).rglob("*.md"))
    skills = sorted((ROOT / ".agents/skills").glob("*/SKILL.md"))
    check_skill_metadata(skills)
    check_markdown_links(ROOT, markdown + skills)
    from workflow import Workflow
    from workflow_checks import CHECKS
    Workflow(ROOT, CHECKS).validate()
    print(f"Static checks passed: {len(markdown)} Markdown files, {len(skills)} skills, reactor POMs and mapper XML", flush=True)


def check_skill_metadata(skills):
    required = {"feature-development", "database-design", "redis-design", "rocketmq-design",
                "concurrency-design", "security-review", "code-review"}
    if not required.issubset({skill.parent.name for skill in skills}):
        raise ValueError("One or more required project skills are missing")
    for skill in skills:
        content = skill.read_text(encoding="utf-8")
        match = re.match(r"^---\nname: ([a-z0-9-]+)\ndescription: (\S[^\n]*)\n---\n", content)
        if not match or match[1] != skill.parent.name:
            raise ValueError(f"Invalid skill frontmatter: {skill.name} in {skill.parent.name}")


def check_markdown_links(root, markdown):
    for path in markdown:
        content = path.read_text(encoding="utf-8-sig")
        content = re.sub(r"(?ms)^`{3,}[^\n]*\n.*?^`{3,}[^\n]*$", "", content)
        for link in re.findall(r"\]\(([^)#]+)(?:#[^)]*)?\)", content):
            if re.match(r"^[a-z][a-z0-9+.-]*:", link, re.I):
                continue
            target = (path.parent / unquote(link)).resolve()
            if not target.is_relative_to(root) or not target.exists():
                raise ValueError(f"Broken local link in {path.relative_to(root)}: {link}")


def scan_secrets(gitleaks):
    fixture = (TEST_FIXTURES / "internal-test-private.pem").read_text(encoding="ascii")
    digest = hashlib.sha256(fixture.encode("ascii")).hexdigest()
    if digest != "c0b7b12b375673139790d8ba386bad4e1c23870f90cd1165191c9f7e222ba6b4":
        raise ValueError("The public test fixture changed. Review it before allowing a new digest.")
    names = run("git", "-c", "core.quotepath=false", "ls-files", "--cached", "--others",
                "--exclude-standard", "-z", capture=True).split("\0")
    output = ROOT / "target"
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="scan-", dir=output) as folder:
        snapshot = Path(folder)
        for name in sorted(set(names) - {""}):
            if name.startswith("infra/local-secrets/") and name != "infra/local-secrets/.gitignore":
                raise ValueError(f"Local secret path in scan inputs: {name}")
            source = ROOT / PurePosixPath(name)
            if source.is_symlink() or not source.is_file():
                continue
            target = snapshot / PurePosixPath(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        run(gitleaks, "dir", "--redact=100", "--verbose", "--config", ".gitleaks.toml", str(snapshot))


def isolated_env():
    app_prefixes = ("SHOP_", "SPRING_", "GATEWAY_", "NACOS_", "REDIS_", "MYSQL_", "HARNESS_", "COMPOSE_")
    launch_overrides = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_ARGS", "MAVEN_OPTS"}
    return {key: value for key, value in os.environ.items()
            if not key.upper().startswith(app_prefixes) and key.upper() not in launch_overrides}


def check_test_reports(started):
    totals = [0, 0, 0, 0]
    for module in REACTOR:
        for path in (module / "target/surefire-reports").glob("TEST-*.xml"):
            if path.stat().st_mtime < started:
                continue
            suite = ET.parse(path).getroot()
            for index, attr in enumerate(("tests", "failures", "errors", "skipped")):
                totals[index] += int(suite.attrib[attr])
    count, failures, errors, skipped = totals
    print(f"Fresh Surefire results: {count} tests, {failures} failures, {errors} errors, {skipped} skipped", flush=True)
    if count == 0 or failures or errors or skipped:
        raise ValueError("Missing or non-passing Surefire results")


def maven_command(args):
    command = [args.maven, "-B", "-ntp", "-f", "pom.xml"]
    if args.offline:
        command.append("-o")
    if args.maven_repo:
        command.append(f"-Dmaven.repo.local={args.maven_repo}")
    return command


def scan_dependencies(args, env):
    started = time.time()
    run(*maven_command(args), "-DskipTests", "-DincludeTestScope=true", "-DoutputReactorProjects=false",
        "-DoutputFormat=json", "-DoutputName=harness.cdx", "package",
        "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom", env=env)
    bom = ROOT / "target/harness.cdx.json"
    if bom.stat().st_mtime < started:
        raise ValueError("The Maven SBOM was not refreshed")
    components = json.loads(bom.read_text(encoding="utf-8"))["components"]
    maven_packages = {component.get("purl", "") for component in components}
    required = ("pkg:maven/org.springframework/spring-core@", "pkg:maven/org.apache.rocketmq/rocketmq-client-java@")
    if not all(any(purl.startswith(prefix) for purl in maven_packages) for prefix in required):
        raise ValueError("The Maven SBOM is missing expected runtime components")
    run(args.osv, "scan", "source", "--lockfile", str(bom.relative_to(ROOT)), env=env)


def maven(args, env, quick=False):
    command = [*maven_command(args), "-DskipTests=false"]
    if quick:
        command.append("-Dtest=" + ",".join(("*Tests", *("!" + item for item in QUICK_EXCLUSIONS))))
    started = time.time()
    run(*command, "verify", env=env)
    check_test_reports(started)


def full(args, env):
    context = args.docker_context or run("docker", "context", "show", capture=True).strip()
    endpoint = run("docker", "context", "inspect", context, "--format",
                   "{{.Endpoints.docker.Host}}", capture=True).strip()
    if not endpoint.startswith(("unix:///", "npipe:////./pipe/")):
        raise ValueError("Choose a Docker context backed by a local socket")
    project = "shop-harness-" + uuid.uuid4().hex[:12]
    compose = ["docker", "--context", context, "compose", "--project-name", project,
               "--env-file", os.devnull, "-f", "infra/quality/compose.yaml"]
    docker_env = {key: value for key, value in isolated_env().items()
                  if key.upper() not in {"DOCKER_HOST", "DOCKER_CONTEXT"}}
    docker_env.update(HARNESS_DB_PASSWORD=secrets.token_hex(16),
                      HARNESS_REDIS_PASSWORD=secrets.token_hex(16),
                      HARNESS_ROCKETMQ_IMAGE=args.rocketmq_image)
    print(f"Starting isolated Compose project {project}", flush=True)
    try:
        run(*compose, "up", "-d", "--wait", "--wait-timeout", "160", env=docker_env)
        run(*compose, "exec", "-T", "mysql-product", "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE flour_messaging_test; '
            "GRANT ALL ON flour_messaging_test.* TO 'harness'@'%'; "
            "CREATE DATABASE flour_order; GRANT ALL ON flour_order.* TO 'harness'@'%';\"", env=docker_env)
        admin = [*compose, "exec", "-T", "rocketmq-broker", "sh", "mqadmin"]
        nameserver = ["-n", "rocketmq-nameserver:9876", "-c", "DefaultCluster"]
        run(*admin, "updateTopic", *nameserver, "-t", "shop-harness-events", "-a", "+message.type=NORMAL",
            env=docker_env)
        run(*admin, "updateSubGroup", *nameserver, "-g", "shop-harness-inbox", "-r", "3", env=docker_env)
        ports = {}
        for service, private in (("mysql-user", 3306), ("mysql-product", 3306), ("mysql-inventory", 3306),
                                 ("redis-security", 6379), ("redis-cache", 6379), ("rocketmq-proxy", 8081)):
            binding = run(*compose, "port", service, str(private), env=docker_env, capture=True).strip()
            host, separator, port = binding.rpartition(":")
            if host != "127.0.0.1" or not separator or not port.isdecimal():
                raise ValueError(f"Unexpected test endpoint for {service}")
            ports[service] = port
        options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        env.update(
            SPRING_PROFILES_INCLUDE="harness",
            HARNESS_USER_DB_URL=f"jdbc:mysql://127.0.0.1:{ports['mysql-user']}/flour_user{options}",
            HARNESS_MESSAGING_DB_URL=f"jdbc:mysql://127.0.0.1:{ports['mysql-product']}/flour_messaging_test{options}",
            HARNESS_ROCKETMQ_ENDPOINT=f"127.0.0.1:{ports['rocketmq-proxy']}",
            HARNESS_DB_PASSWORD=docker_env["HARNESS_DB_PASSWORD"],
            SHOP_PRODUCT_MYSQL_HOST="127.0.0.1", SHOP_PRODUCT_MYSQL_PORT=ports["mysql-product"],
            SHOP_PRODUCT_MYSQL_USER="harness", SHOP_PRODUCT_MYSQL_PASSWORD=docker_env["HARNESS_DB_PASSWORD"],
            SHOP_ORDER_MYSQL_HOST="127.0.0.1", SHOP_ORDER_MYSQL_PORT=ports["mysql-product"],
            SHOP_ORDER_MYSQL_USER="harness", SHOP_ORDER_MYSQL_PASSWORD=docker_env["HARNESS_DB_PASSWORD"],
            SHOP_INVENTORY_MYSQL_HOST="127.0.0.1", SHOP_INVENTORY_MYSQL_PORT=ports["mysql-inventory"],
            SHOP_INVENTORY_MYSQL_USER="harness", SHOP_INVENTORY_MYSQL_PASSWORD=docker_env["HARNESS_DB_PASSWORD"],
        )
        for prefix, port in (("SHOP_GATEWAY_REDIS", ports["redis-security"]),
                             ("SHOP_CACHE_REDIS", ports["redis-cache"])):
            env.update({prefix + "_HOST": "127.0.0.1", prefix + "_PORT": port,
                        prefix + "_PASSWORD": docker_env["HARNESS_REDIS_PASSWORD"]})
        maven(args, env)
    finally:
        run(*compose, "down", "--volumes", env=docker_env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("lint", "quick", "full", "secret-scan", "dependency-scan"))
    parser.add_argument("--maven", default="mvn.cmd" if os.name == "nt" else "mvn")
    parser.add_argument("--maven-repo")
    parser.add_argument("--gitleaks", default="gitleaks")
    parser.add_argument("--osv", default="osv-scanner")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--base", help="Git base ref for checking changed lines in CI")
    parser.add_argument("--docker-context")
    parser.add_argument("--rocketmq-image", default="apache/rocketmq:5.5.0",
                        help="RocketMQ image with nameserver, broker and mqadmin scripts")
    args = parser.parse_args()
    lint(args.base)
    if args.mode == "lint":
        return
    if args.mode == "secret-scan":
        scan_secrets(args.gitleaks)
        return
    env = isolated_env()
    if args.mode == "dependency-scan":
        scan_dependencies(args, env)
        return
    env.update(
        SPRING_DOCKER_COMPOSE_ENABLED="false",
        SPRING_CLOUD_NACOS_DISCOVERY_ENABLED="false",
        SHOP_GATEWAY_INTERNAL_PUBLIC_KEY_LOCATION=(TEST_FIXTURES / "internal-test-public.pem").as_uri(),
        SHOP_USER_JWT_PRIVATE_KEY_LOCATION=(TEST_FIXTURES / "internal-test-private.pem").as_uri(),
        SHOP_GATEWAY_JWT_PUBLIC_KEY_LOCATION=(TEST_FIXTURES / "internal-test-public.pem").as_uri(),
    )
    if args.mode == "quick":
        maven(args, env, quick=True)
    else:
        full(args, env)


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"Quality gate failed: {error}", file=sys.stderr)
        sys.exit(1)
