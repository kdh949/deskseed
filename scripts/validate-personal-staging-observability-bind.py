#!/usr/bin/env python3
"""Fail closed on unsafe private-observability host port bindings."""

from __future__ import annotations

import ipaddress
import json
import subprocess
import sys
from collections.abc import Iterable
from typing import Any


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(2)


def parse_address(raw: object, label: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    value = str(raw).strip()
    if value.startswith("[") and value.endswith("]"):
        value = value[1:-1]
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        fail(f"Personal-staging observability {label} must be a literal IP address.")

    mapped = getattr(address, "ipv4_mapped", None)
    effective_address = mapped or address
    if effective_address.is_unspecified:
        fail(f"Personal-staging observability {label} must not be an unspecified address.")
    if effective_address.is_global:
        fail(f"Personal-staging observability {label} must not be globally routable.")
    return address


def host_addresses() -> set[ipaddress.IPv4Address | ipaddress.IPv6Address]:
    try:
        result = subprocess.run(
            ["ip", "-o", "addr", "show"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        fail("Could not read host addresses with ip -o addr show; refusing observability deployment.")

    addresses: set[ipaddress.IPv4Address | ipaddress.IPv6Address] = set()
    for line in result.stdout.splitlines():
        fields = line.split()
        for family in ("inet", "inet6"):
            if family not in fields:
                continue
            candidate = fields[fields.index(family) + 1].split("/", 1)[0]
            try:
                addresses.add(ipaddress.ip_address(candidate))
            except ValueError:
                continue
    return addresses


def only_published_port(service: dict[str, Any], service_name: str, target: int) -> dict[str, Any]:
    ports = service.get("ports") or []
    if len(ports) != 1:
        fail(f"{service_name} must publish exactly one private observability port.")
    port = ports[0]
    if int(port.get("target", -1)) != target or int(port.get("published", -1)) != target:
        fail(f"{service_name} must publish only {target}:{target} for personal-staging observability.")
    if not port.get("host_ip"):
        fail(f"{service_name} must set an explicit private host bind address.")
    return port


def main(model: dict[str, Any]) -> None:
    services = model.get("services")
    if not isinstance(services, dict):
        fail("Rendered Compose model has no services map.")

    try:
        backend = only_published_port(services["backend"], "backend", 9090)
        alloy = only_published_port(services["alloy"], "alloy", 12345)
    except KeyError as error:
        fail(f"Rendered Compose model is missing required observability service: {error.args[0]}.")

    backend_address = parse_address(backend["host_ip"], "backend bind address")
    alloy_address = parse_address(alloy["host_ip"], "Alloy bind address")
    if backend_address != alloy_address:
        fail("Backend and Alloy must use the same private observability bind address.")

    if backend_address not in host_addresses():
        fail("Personal-staging observability bind address is not assigned to this host.")


if __name__ == "__main__":
    try:
        main(json.load(sys.stdin))
    except json.JSONDecodeError:
        fail("Could not parse rendered Compose JSON for observability bind validation.")
