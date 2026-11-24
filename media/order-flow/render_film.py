"""Render this Canvas film with the installed vibe-knowledge-film engine."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import subprocess
import sys
from tempfile import TemporaryDirectory


def inline_page(engine, root):
    html = (root / "index.html").read_text(encoding="utf-8")

    def inline(match):
        src = match.group(1)
        script = engine / src.removeprefix("engine/") if src.startswith("engine/") else root / src
        return "<script>\n" + script.read_text(encoding="utf-8") + "\n</script>"

    return re.sub(r'<script src="([^"]+)"></script>', inline, html)


def open_page(playwright, html):
    browser = playwright.chromium.launch(
        channel="chrome", args=["--disable-lcd-text", "--force-color-profile=srgb"]
    )
    page = browser.new_page(viewport={"width": 1920, "height": 1080})
    page.set_content(html)
    page.wait_for_function("window.READY === true", timeout=15000)
    return browser, page


def render_part(html, ffmpeg, output, start, stop, fps):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as playwright:
        browser, page = open_page(playwright, html)
        try:
            args = [
                ffmpeg, "-v", "error", "-y", "-f", "image2pipe", "-vcodec", "mjpeg",
                "-framerate", str(fps), "-i", "-", "-c:v", "libx264", "-preset", "veryfast",
                "-threads", "2", "-crf", "17", "-pix_fmt", "yuv420p", str(output),
            ]
            with subprocess.Popen(args, stdin=subprocess.PIPE) as encoder:
                for first in range(start, stop, 16):
                    frames = page.evaluate(
                        """([first, count, fps]) => {
                            const canvas = document.getElementById('stage');
                            return Array.from({length: count}, (_, i) => {
                                seek((first + i) / fps);
                                return canvas.toDataURL('image/jpeg', .95).split(',', 2)[1];
                            });
                        }""",
                        [first, min(16, stop - first), fps],
                    )
                    for frame in frames:
                        encoder.stdin.write(base64.b64decode(frame))
                    if (first - start) % 320 == 0:
                        print(f"{output.stem} {first - start}/{stop - start}", flush=True)
                encoder.stdin.close()
                if encoder.wait():
                    raise subprocess.CalledProcessError(encoder.returncode, args)
        finally:
            browser.close()


def render_movie(engine, audio, ffmpeg, root, jobs, head):
    from playwright.sync_api import sync_playwright

    html = inline_page(engine, root)
    target = root / "renders"
    target.mkdir(exist_ok=True)
    with sync_playwright() as playwright:
        browser, page = open_page(playwright, html)
        try:
            spec = page.evaluate("({end: END, fps: FPS, meta: META, events: events()})")
        finally:
            browser.close()
    duration = min(30, spec["end"]) if head else spec["end"]
    fps = spec["fps"]
    total = round(duration * fps)
    if head:
        spec["end"] = duration
        spec["events"] = [event for event in spec["events"] if event[0] < duration]

    with TemporaryDirectory(prefix="build-", dir=target) as directory:
        work = Path(directory)
        parts = [work / f"part-{i}.mp4" for i in range(jobs)]
        with ThreadPoolExecutor(max_workers=jobs) as workers:
            futures = [
                workers.submit(render_part, html, ffmpeg, part,
                               total * i // jobs, total * (i + 1) // jobs, fps)
                for i, part in enumerate(parts)
            ]
            for future in futures:
                future.result()

        manifest = work / "parts.txt"
        manifest.write_text("".join(f"file '{part.name}'\n" for part in parts), encoding="utf-8")
        wav = work / "score.wav"
        score = audio.synth(spec)
        peak = abs(score).max() or 1
        audio.write_wav(wav, score / peak * 10 ** (-3 / 20))
        result = target / ("head.mp4" if head else "final.mp4")
        subprocess.run([
            ffmpeg, "-v", "error", "-y", "-f", "concat", "-safe", "1", "-i", str(manifest),
            "-i", str(wav), "-af", "loudnorm=I=-18:TP=-1.5:LRA=14", "-map", "0:v:0",
            "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
            "-t", str(duration), "-movflags", "+faststart", str(result),
        ], check=True)
        print(result, flush=True)


def main():
    from imageio_ffmpeg import get_ffmpeg_exe

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("engine", type=Path, help="Path to the vibe-knowledge-film engine directory")
    parser.add_argument("--jobs", type=int, choices=range(1, 7), default=3)
    parser.add_argument("--head", action="store_true", help="Render the first 30 seconds")
    args = parser.parse_args()
    sys.path.insert(0, str(args.engine.resolve()))
    import audio

    render_movie(args.engine.resolve(), audio, get_ffmpeg_exe(), Path(__file__).resolve().parent, args.jobs, args.head)


if __name__ == "__main__":
    main()
