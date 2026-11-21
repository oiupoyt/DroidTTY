package com.asnidev.sysreadout.data

/**
 * A complete look in one tap: theme, home layout, always-on display and lock
 * screen chosen to go together. Applying one only changes what it names; access,
 * PIN, pinned apps and log rows stay as they are. Nothing turns the screen on or
 * locks the phone by itself: the always-on display and lock screen still follow
 * their own on/off switches, which a setup leaves alone.
 */
data class Setup(
    val name: String,
    val summary: String,
    val theme: Theme,
    val aodLook: AodLook,
    val aodColor: AodColor = AodColor.THEME,
    val aodScale: Float = 1f,
    val lockPreset: LockPreset,
    val entryStyle: EntryStyle,
    val showLog: Boolean = true,
    val logLayout: LogLayout = LogLayout.CLASSIC,
) {
    fun applyTo(p: LauncherPrefs): LauncherPrefs = p.copy(entryStyle = entryStyle, showLog = showLog, logLayout = logLayout)

    fun applyTo(c: CoverPrefs): CoverPrefs = c.copy(aodLook = aodLook, aodColor = aodColor, aodScale = aodScale, lockPreset = lockPreset)

    /** True when every part this setup sets is as it set it. */
    fun isApplied(t: Theme, p: LauncherPrefs, c: CoverPrefs): Boolean =
        t == theme && p.entryStyle == entryStyle && p.showLog == showLog && p.logLayout == logLayout &&
            c.aodLook == aodLook && c.aodColor == aodColor && c.aodScale == aodScale && c.lockPreset == lockPreset
}

object Setups {
    val all = listOf(
        Setup(
            "arch workstation",
            "green phosphor terminal, neofetch always-on, a full arch boot to unlock",
            Presets.greenP1.theme, AodLook.NEOFETCH, lockPreset = LockPreset.ARCH, entryStyle = EntryStyle.FROSTED,
        ),
        Setup(
            "retro dos",
            "amber dos screen, pong always-on, a dos prompt to unlock",
            Presets.amber.theme, AodLook.PONG, lockPreset = LockPreset.DOS, entryStyle = EntryStyle.HIGHLIGHT,
        ),
        Setup(
            "hacker",
            "green on black, matrix rain always-on, \"wake up\" to unlock",
            Presets.greenP1.theme, AodLook.MATRIX, lockPreset = LockPreset.WAKEUP, entryStyle = EntryStyle.BARE,
            logLayout = LogLayout.FEED,
        ),
        Setup(
            "calm minimal",
            "plain white on black, no log, a small clock always-on, a quiet login",
            Presets.minimal.theme, AodLook.CLOCK, aodColor = AodColor.DIM, lockPreset = LockPreset.LOGIN,
            entryStyle = EntryStyle.BARE, showLog = false,
        ),
        Setup(
            "demoscene",
            "neon arcade colours, plasma always-on, a commodore 64 boot",
            Presets.arcade.theme, AodLook.PLASMA, lockPreset = LockPreset.C64, entryStyle = EntryStyle.INVERTED,
        ),
        Setup(
            "sysadmin",
            "paper-white terminal, htop meters always-on, an ssh login",
            Presets.paper.theme, AodLook.HTOP, lockPreset = LockPreset.SSH, entryStyle = EntryStyle.FROSTED,
            logLayout = LogLayout.FEED,
        ),
        Setup(
            "cozy fire",
            "amber terminal, doom fire always-on, a bios flash to unlock",
            Presets.amber.theme, AodLook.FIRE, lockPreset = LockPreset.BIOS, entryStyle = EntryStyle.FROSTED,
        ),
        Setup(
            "aquarium",
            "lime terminal, fish and bubbles always-on, a grub menu",
            Presets.lime.theme, AodLook.AQUARIUM, lockPreset = LockPreset.GRUB, entryStyle = EntryStyle.HIGHLIGHT,
        ),
    )
}
