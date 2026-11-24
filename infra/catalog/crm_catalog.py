"""Prepare and import a local CRM catalog snapshot."""

import argparse
from collections import Counter
from decimal import Decimal
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit, urlunsplit


WEIGHT = re.compile(r"(\d+(?:\.\d+)?)\s*(kg|g|千克|公斤|克)", re.IGNORECASE)
PACKAGING = re.compile(
    r"(?:吊带|捆扎)?\d+(?:[×*xX]\d+)*[袋把提盒扎]|简袋|吨包|自带吨包|箱式散装"
)
SUFFIX = re.compile(r"\(([^()]*)\)\s*$|（([^（）]*)）\s*$")
FACETS = (
    ("category", "品类", "atrb"),
    ("quotaCategory", "定额分类", "art4"),
    ("brand", "品牌", "atrh"),
    ("productType", "类别", "atru"),
    ("packaging", "包装形式", "atrq"),
    ("channel", "渠道", "atrt"),
    ("specification", "规格", "atro"),
    ("grade", "星级", "atrs"),
    ("usage", "用途", "atrg"),
)


def image_url(value):
    url = urlsplit(value or "")
    if url.netloc == "crm.wudeli.com" and url.scheme in ("http", "https") and url.path.startswith("/img/"):
        return urlunsplit(("https", url.netloc, url.path, "", ""))
    return None


class ProductContent(HTMLParser):
    def __init__(self):
        super().__init__()
        self.text = []
        self.images = []

    def handle_starttag(self, tag, attrs):
        if tag == "img":
            url = image_url(dict(attrs).get("src"))
            if url and url not in self.images:
                self.images.append(url)

    def handle_data(self, data):
        if data.strip():
            self.text.append(data.strip())


def product_name(name):
    while match := SUFFIX.search(name):
        parenthetical = match.group(1) or match.group(2)
        if not parenthetical or not (WEIGHT.fullmatch(parenthetical) or PACKAGING.fullmatch(parenthetical)):
            break
        remainder = name[:match.start()].rstrip()
        if not remainder:
            break
        name = remainder
    return name


def grams(label):
    match = WEIGHT.fullmatch(label or "")
    if match is None:
        return None
    amount, unit = match.groups()
    weight = Decimal(amount) * (1000 if unit.lower() in ("kg", "千克", "公斤") else 1)
    return int(weight) if weight > 0 and weight == weight.to_integral_value() else None


def price_fen(detail_price, list_price):
    if detail_price is None or detail_price == 0:
        if list_price is not None:
            raise ValueError("A priced product has an empty or zero detail quote")
        return None
    amount = Decimal(str(detail_price)) * 100
    if detail_price != list_price or amount <= 0 or amount != amount.to_integral_value():
        raise ValueError("Conflicting or invalid quote")
    return int(amount)


def prepare(capture, captured_on):
    details = {item["id"]: item for item in capture["details"]}
    listed = {item["id"]: item for item in capture["rows"]}
    if not len(details) == len(listed) == len(capture["details"]) == len(capture["rows"]) == capture["total"]:
        raise ValueError("Incomplete or duplicate CRM rows")
    if listed.keys() != details.keys():
        raise ValueError("List and detail product IDs differ")

    dictionaries = capture["dictionaries"]
    grouped = {}
    for source_id, listed_item in listed.items():
        item = details[source_id]
        if any(item.get(key) != listed_item.get(key) for key in ("sn", "fullName", "atrb")):
            raise ValueError(f"Mismatched list and detail for {source_id}")

        sku_code = item["sn"]
        name = product_name(item["fullName"].strip())
        brand = item.get("atrh")
        category = dictionaries["atrb"][item["atrb"]]
        # A missing brand provides insufficient identity to merge another item.
        grouping = (category, brand, name, None if brand else sku_code)
        code = hashlib.sha256(json.dumps(grouping, ensure_ascii=False).encode()).hexdigest()[:16]
        cover = image_url(item.get("imgrealpath"))
        product = grouped.setdefault(grouping, {
            "spuCode": f"CRM-{code}",
            "name": name,
            "categoryName": category,
            "brand": brand,
            "usage": item.get("atrg"),
            "imageUrl": cover,
            "skus": [],
        })
        content = ProductContent()
        content.feed(item.get("procontent", ""))
        weight_label = item.get("productWeight")
        product["skus"].append({
            "sourceId": source_id,
            "skuCode": sku_code,
            "materialCode": item.get("matnr"),
            "name": item["fullName"],
            "specName": listed_item.get("specification"),
            "sourceWeight": weight_label,
            "weightGram": grams(weight_label),
            "priceFen": price_fen(item.get("price"), listed_item.get("price")),
            "barcode": item.get("barCode"),
            **{field: dictionaries.get(source_key, {}).get(item.get(source_key), item.get(source_key))
               for field, _, source_key in FACETS if field != "category"},
            "imageUrl": cover,
            "detailImages": content.images,
            "description": " ".join(content.text) or None,
            "sourceNote": (item.get("description") or "").strip() or None,
        })
    return {
        "source": capture["source"],
        "company": capture["company"],
        "capturedOn": captured_on,
        "currency": "CNY",
        "spuGrouping": "INFERRED_FROM_CATEGORY_BRAND_AND_BASE_NAME",
        "skuCount": capture["total"],
        "facets": [{"key": field, "label": label, "options": list(dictionaries.get(source_key, {}).values())}
                   for field, label, source_key in FACETS],
        "products": list(grouped.values()),
    }


def validate(catalog):
    products = catalog["products"]
    skus = [sku for product in products for sku in product["skus"]]
    if not products or any(not product["skus"] or not product["name"] or not product["categoryName"] for product in products):
        raise ValueError("Invalid SPU or missing SKU")
    if len(skus) != catalog["skuCount"]:
        raise ValueError("SKU count differs from the catalog")
    for records, field in ((products, "spuCode"), (skus, "skuCode"), (skus, "sourceId")):
        values = [record[field] for record in records]
        if any(not isinstance(value, str) or not value.strip() for value in values):
            raise ValueError(f"Missing identifier: {field}")
        if len(set(value.casefold() for value in values)) != len(records):
            raise ValueError(f"Duplicate identifier: {field}")
    return len(products), len(skus), dict(Counter(p["categoryName"] for p in products))


def mysql(sql):
    # Scope all writes to the project's local product database. MYSQL_PWD is passed via environment.
    port = int(os.environ.get("SHOP_PRODUCT_MYSQL_PORT", "3308"))
    user = os.environ.get("SHOP_PRODUCT_MYSQL_USER", "shop_product")
    if not os.environ.get("MYSQL_PWD"):
        raise ValueError("Set MYSQL_PWD to the local product database password")
    result = subprocess.run(
        ["mysql", "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}",
         f"--user={user}", "--default-character-set=utf8mb4", "--database=flour_product",
         "--batch", "--raw", "--skip-column-names", "--connect-timeout=5"],
        input=sql, capture_output=True, check=True, encoding="utf-8",
    )
    return result.stdout


def import_snapshot(catalog):
    validate(catalog)
    payload = json.dumps(catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    digest = hashlib.sha256(payload).hexdigest()
    # Hex literals round-trip UTF-8 without SQL escaping or shell interpolation.
    mysql(f"""START TRANSACTION;
        INSERT INTO crm_catalog_snapshot (payload_sha256, payload)
        VALUES (UNHEX('{digest}'), CONVERT(UNHEX('{payload.hex()}') USING utf8mb4))
        ON DUPLICATE KEY UPDATE id = id;
        COMMIT;""")
    snapshot_id, stored = mysql(
        f"SELECT id, payload FROM crm_catalog_snapshot WHERE payload_sha256 = UNHEX('{digest}');"
    ).rstrip("\n").split("\t", 1)
    if json.loads(stored) != catalog:
        raise ValueError(f"Snapshot {snapshot_id} differs from the project file")
    return snapshot_id, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_command = commands.add_parser("prepare")
    prepare_command.add_argument("capture", type=Path)
    prepare_command.add_argument("catalog", type=Path)
    prepare_command.add_argument("--captured-on", required=True)
    import_command = commands.add_parser("import")
    import_command.add_argument("catalog", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            raw = json.loads(args.capture.read_text(encoding="utf-8"))
            catalog = prepare(raw, args.captured_on)
            spu_count, sku_count, categories = validate(catalog)
            args.catalog.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        else:
            catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
            spu_count, sku_count, categories = validate(catalog)
            snapshot_id, digest = import_snapshot(catalog)
            print(f"MySQL snapshot {snapshot_id}, SHA-256 {digest}, full JSON comparison passed")
        print(json.dumps({"spuCount": spu_count, "skuCount": sku_count, "spuByCategory": categories}, ensure_ascii=False))
    except subprocess.CalledProcessError as error:
        parser.exit(1, error.stderr)
    except (KeyError, OSError, ValueError) as error:
        parser.exit(1, f"CRM catalog failed: {error}\n")


if __name__ == "__main__":
    main()
