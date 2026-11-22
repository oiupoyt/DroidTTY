"""Renders tools/og/card.html to site/assets/og.png (1200x630), the image link previews show.

Needs Playwright with Chromium: python3 tools/og/render.py
"""
import asyncio
import pathlib

from playwright.async_api import async_playwright

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent.parent / "site" / "assets" / "og.png"


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1200, "height": 630})
        await page.goto((HERE / "card.html").as_uri())
        await page.evaluate("document.fonts.ready")
        await page.wait_for_timeout(300)
        await page.screenshot(path=str(OUT))
        await browser.close()
    print(OUT)


asyncio.run(main())
