"""Renders tools/og/card.html to site/assets/og.png (1200x630), the image link previews show,
and to metadata/en-US/images/featureGraphic.png (1024x500) for F-Droid.

Needs Playwright with Chromium: python3 tools/og/render.py
"""
import asyncio
import pathlib

from playwright.async_api import async_playwright

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent.parent / "site" / "assets" / "og.png"
FEATURE = HERE.parent.parent / "metadata" / "en-US" / "images" / "featureGraphic.png"


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1200, "height": 630})
        await page.goto((HERE / "card.html").as_uri())
        await page.evaluate("document.fonts.ready")
        await page.wait_for_timeout(300)
        await page.screenshot(path=str(OUT))
        # The F-Droid feature graphic: the same card at the store's 1024x500.
        await page.set_viewport_size({"width": 1024, "height": 500})
        await page.goto((HERE / "card.html").as_uri() + "?feature")
        await page.evaluate("document.fonts.ready")
        await page.wait_for_timeout(300)
        await page.screenshot(path=str(FEATURE))
        await browser.close()
    print(OUT)
    print(FEATURE)


asyncio.run(main())
