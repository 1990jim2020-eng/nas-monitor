import csv
import json
import math
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path


def numeric_reading(value, minimum, maximum):
    try:
        number = float(value)
    except (ValueError, TypeError):
        return None
    return round(number, 1) if math.isfinite(number) and minimum <= number <= maximum else None


def parse_ipmi(output):
    result = {"temperatures": {}, "fans": {}, "voltages": {}}
    for line in output.splitlines():
        parts = [part.strip() for part in line.split("|")]
        if len(parts) != 5:
            continue
        label, status, reading = parts[0], parts[2], parts[4]
        match = re.fullmatch(r"(-?\d+(?:\.\d+)?)\s+(degrees C|RPM|Volts)", reading)
        if not match or status in ("ns", "na"):
            continue
        unit = match.group(2)
        limits = {"degrees C": (-20, 150), "RPM": (0, 50000), "Volts": (0, 100)}
        value = numeric_reading(match.group(1), *limits[unit])
        if value is None:
            continue
        group = {"degrees C": "temperatures", "RPM": "fans", "Volts": "voltages"}[unit]
        result[group][label] = {"value": round(float(match.group(1)), 2) if unit == "Volts" else value, "status": status}
    return result


def collect(sys_root=Path("/sys"), run=subprocess.run):
    snapshot = {"status": "ok", "updated_at": time.time(), "source": "sysfs-smart-ipmi",
                "temperatures": {}, "disks": {}, "disk_details": {}, "fans": {}, "voltages": {}, "errors": []}
    for chip in sorted((sys_root / "class/hwmon").glob("hwmon*")):
        try:
            name = (chip / "name").read_text().strip()
        except OSError:
            continue
        for sensor in sorted(chip.glob("temp*_input")):
            try:
                label_path = sensor.with_name(sensor.name.replace("_input", "_label"))
                label = label_path.read_text().strip() if label_path.exists() else sensor.stem
                value = numeric_reading(float(sensor.read_text()) / 1000, -20, 150)
            except (OSError, ValueError):
                continue
            if value is not None:
                snapshot["temperatures"][f"{name} · {label}"] = value
                if name == "coretemp" and label.startswith("Package id"):
                    snapshot["cpu_temp"] = value
    for device in sorted((sys_root / "block").iterdir()):
        if not re.fullmatch(r"sd[a-z]+|nvme\d+n\d+", device.name):
            continue
        try:
            response = run(["smartctl", "-n", "standby", "-j", "-i", "-A", "/dev/" + device.name],
                           capture_output=True, text=True, timeout=8)
            data = json.loads(response.stdout)
            temperature = numeric_reading((data.get("temperature") or {}).get("current"), -20, 150)
            rotating = (device / "queue/rotational").read_text().strip() == "1"
            snapshot["disk_details"][device.name] = {"model": data.get("model_name", ""), "rotational": rotating,
                                                    "temperature": temperature}
            if temperature is not None:
                snapshot["disks"][device.name] = temperature
                snapshot["temperatures"]["disk · " + device.name] = temperature
        except (OSError, ValueError, subprocess.TimeoutExpired) as error:
            snapshot["errors"].append(device.name + ": " + type(error).__name__)
    if Path("/dev/ipmi0").exists():
        try:
            response = run(["ipmitool", "-I", "open", "sdr", "elist"],
                           capture_output=True, text=True, timeout=15)
            if response.returncode != 0:
                raise RuntimeError("IPMI read failed")
            bmc = parse_ipmi(response.stdout)
            snapshot["fans"] = bmc["fans"]
            snapshot["voltages"] = bmc["voltages"]
            snapshot["temperatures"].update({"BMC · " + label: item["value"]
                                              for label, item in bmc["temperatures"].items()})
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            snapshot["errors"].append("IPMI: " + type(error).__name__)
    snapshot["updated_at"] = time.time()
    if snapshot["errors"]:
        snapshot["status"] = "partial"
    return snapshot


def save_snapshot(snapshot, path):
    descriptor, temporary = tempfile.mkstemp(dir=str(path.parent), prefix=".hardware-")
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(snapshot, stream, ensure_ascii=False)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def append_bmc_curve(snapshot, path):
    if not path.exists() or not snapshot["fans"]:
        return
    with path.open() as stream:
        columns = stream.readline().strip().split(",")
    rotating = [details["temperature"] for details in snapshot["disk_details"].values()
                if details["rotational"] and details["temperature"] is not None]
    row = {"timestamp": time.strftime("%Y-%m-%d %H:%M:%S"), "CPU_C": snapshot.get("cpu_temp", ""),
           "HDD_avg": round(sum(rotating) / len(rotating), 1) if rotating else "", "PWM": "",
           "fan1_RPM": snapshot["fans"].get("CPU0_FAN", {}).get("value", ""),
           "fan2_RPM": snapshot["fans"].get("SYS_FAN1", {}).get("value", "")}
    row.update({name + "_C": temperature for name, temperature in snapshot["disks"].items()})
    with path.open("a") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, extrasaction="ignore")
        writer.writerow(row)


if __name__ == "__main__":
    data = collect()
    save_snapshot(data, Path("/logs/hardware_snapshot.json"))
    append_bmc_curve(data, Path("/logs/temp_curve.csv"))
