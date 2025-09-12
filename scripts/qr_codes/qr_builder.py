#!/usr/bin/env python3
"""
QR Code Builder (lab-friendly)

- Purpose: Generate a PNG (and optional ASCII preview) that encodes any HTTP/HTTPS URL.
- Why: Lets me distribute a link (e.g., file on my lab AP) by scanning a QR.
- Safety: We only accept http/https and require a host. Everything printed uses prints().
"""

# ---- standard library imports ----
import argparse         # parse command-line options without writing our own parser
import os               # paths + clearing the screen for the banner
import sys              # exit + stdout + argv + stdin
import time             # tiny delays for typewriter-style output
import textwrap         # indent/strip ASCII art nicely
from urllib.parse import urlparse  # robust URL parsing (scheme, host, etc.)

# ---- tiny ANSI style (harmless if unsupported terminals) ----
w = '\033[0m'      # reset all styles
d = '\033[90m'     # dim gray used in the banner

# ---- sensible default URL (user can override with -u/--url or prompt) ----
DEFAULT_URL = "http://10.42.0.1:8000/app-debug.apk"

# ---------------------------------------------------------------------------
# prints(): the ONLY way we show text
# - We print character-by-character for a smooth UX.
# - Every message to the user flows through here, so styling is consistent.
# ---------------------------------------------------------------------------
def prints(s: str, end: str = "\n") -> None:
    """Slow-print text. Always use this instead of print()."""
    for ch in s:
        sys.stdout.write(ch)
        sys.stdout.flush()
        time.sleep(0.004)  # small delay keeps it readable without being slow
    if end:
        sys.stdout.write(end)
        sys.stdout.flush()

# ---------------------------------------------------------------------------
# banner(): eye-candy + clear the screen so the tool feels 'fresh' each run
# ---------------------------------------------------------------------------
def banner() -> None:
    """Clear the terminal and draw a small ASCII banner."""
    os.system("cls" if os.name == "nt" else "clear")  # Windows vs *nix
    art = r"""
     ____                      _____
    |  _ \ _   _  __ _ _ __   |  ___|____  __
    | |_) | | | |/ _` | '_ \  | |_ / _ \ \/ /
    |  _ <| |_| | (_| | | | | |  _| (_) >  <
    |_| \_\\__, |\__,_|_| |_| |_|  \___/_/\_\
           |___/

            ,
            |`-.__             _=,_
            / ' _/          o_/6 /#\
           ****`            \__ |##/
          /    }              ='|--\
         /  \ /                 /   #'-.
     \ /`   \\\                 \#|_   _'-. /
      `\    /_\\                 |/ \_( # |" 
       `~~~~~``~`               C/ ,--___/
    """
    # dim the banner so it doesn't shout at the reader
    for line in textwrap.dedent(art).strip("\n").splitlines():
        prints(f"{d}{line}{w}")

# ---------------------------------------------------------------------------
# validate_or_die(): keep it simple and strict
# - Only http/https allowed (common QR usage; avoids odd schemes).
# - Host must exist (netloc is the host:port part).
# ---------------------------------------------------------------------------
def validate_or_die(u: str) -> str:
    """Validate URL format; exit with a friendly message if invalid."""
    p = urlparse((u or "").strip())
    if p.scheme not in ("http", "https"):
        prints("Error: URL must start with http:// or https://")
        sys.exit(1)
    if not p.netloc:
        prints("Error: URL must include a host (e.g., 10.42.0.1)")
        sys.exit(1)
    # .geturl() keeps exactly what the user typed (including port/path/query)
    return p.geturl()

# ---------------------------------------------------------------------------
# show_help_and_exit(): custom help so the output still uses prints()
# ---------------------------------------------------------------------------
def show_help_and_exit(parser: argparse.ArgumentParser) -> None:
    """Manual help text; mirrors argparse options but routes via prints()."""
    usage = f"""
Usage: {os.path.basename(sys.argv[0])} [options]

Options:
  -u, --url URL          URL to encode (default: {DEFAULT_URL})
  -o, --output FILE      Output PNG filename (default: qr.png)
      --scale N          Module scale (pixel size per QR module) (default: 8)
      --quiet-zone N     Border (modules) around the QR (default: 2)
      --ecc L|M|Q|H      Error correction level; higher survives damage better
                         but stores less data per version (default: M)
      --ascii            Also print an ASCII preview in the terminal
  -h, --help             Show this help and exit
"""
    prints(textwrap.dedent(usage).strip("\n"))
    sys.exit(0)

# ---------------------------------------------------------------------------
# main(): wire up CLI, import segno, build the QR, save + (optionally) preview
# ---------------------------------------------------------------------------
def main() -> None:
    # segno is a tiny, pure-Python QR lib (no pillow dependency).
    try:
        import segno
    except Exception:
        prints("Error: Python package 'segno' is not installed.\n"
               "Install it with:  pip install segno")
        sys.exit(1)

    # We disable argparse's built-in help so we can control all text via prints()
    ap = argparse.ArgumentParser(add_help=False)
    # URL: optional; if missing we will prompt interactively
    ap.add_argument("-u", "--url")
    # Output file: PNG path; we will add '.png' if the user forgot it
    ap.add_argument("-o", "--output", default="qr.png")
    # Scale: how large each QR module is rendered in pixels
    ap.add_argument("--scale", type=int, default=8)
    # Quiet zone: how many blank modules surround the QR (helps scanners lock on)
    ap.add_argument("--quiet-zone", type=int, default=2, dest="quiet")
    # ECC: QR error-correction level; H is most robust, L stores most data
    ap.add_argument("--ecc", choices=list("LMQH"), default="M")
    # ASCII: quick terminal preview; handy if I don’t want to open an image
    ap.add_argument("--ascii", action="store_true")
    # Help: our own help switch
    ap.add_argument("-h", "--help", action="store_true")
    args = ap.parse_args()

    if args.help:
        show_help_and_exit(ap)

    banner()

    # If user didn't pass -u/--url, ask interactively (keeps the tool friendly)
    url = args.url
    if not url:
        prints(f"Enter URL to encode [{DEFAULT_URL}]: ", end="")
        entered = sys.stdin.readline().strip()
        url = entered or DEFAULT_URL

    # Enforce basic URL sanity so we don't encode junk by accident
    url = validate_or_die(url)

    # Normalize output filename so it's always a .png
    out = args.output if args.output.lower().endswith(".png") else (args.output + ".png")

    # Build the QR with requested ECC; save at desired scale and quiet zone
    try:
        qr = segno.make(url, error=args.ecc)  # segno picks the smallest QR version that fits
        qr.save(out, scale=args.scale, border=args.quiet)
    except Exception as e:
        # Any error here is unexpected (e.g., permission/path issues)
        prints(f"Error: failed to generate QR → {e}")
        sys.exit(1)

    # Friendly success summary
    prints("")
    prints(f"✓ QR code saved: {os.path.abspath(out)}")
    prints(f"  Encodes: {url}")
    prints(f"  ECC={args.ecc}, scale={args.scale}, quiet zone={args.quiet}")

    # Optional terminal preview (great for quick dry-runs over SSH)
    if args.ascii:
        prints("\nASCII preview:\n")
        try:
            preview = qr.terminal(compact=True)  # compact=True keeps it narrower
            prints(preview)
        except Exception as e:
            prints(f"(ASCII preview unavailable: {e})")

    # Practical tip so users avoid “why won’t it open?” confusion on mobile
    prints("\nTip: open the PNG on-screen or print it; scan with the phone camera.")
    prints("If the URL is hosted on my lab AP, ensure the handset is on the same SSID.")

# Standard Python entry point
if __name__ == "__main__":
    main()
