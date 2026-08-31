package com.astrolexis.pyblock.data.crypto

import android.content.Context
import java.security.MessageDigest

/**
 * Deterministic, human-friendly **cosmic** name for a PayNym — the same idea as
 * Samourai/paynym.rs "robot names", but astronomical/universal (AstroLexis aesthetic).
 * Derived purely from the payment code, so iOS and Android show the SAME name for a
 * given code ("Astral Vela 3F"). The word lists + indexing MUST stay byte-identical
 * to the iOS PaynymName.swift.
 */
object PaynymName {

    fun cosmic(code: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        val adj = ADJECTIVES[(((h[0].toInt() and 0xff) shl 8) or (h[1].toInt() and 0xff)) % ADJECTIVES.size]
        val noun = NOUNS[(((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)) % NOUNS.size]
        val suffix = "%02X".format(h[4].toInt() and 0xff)
        return "$adj $noun $suffix"
    }

    /** This device's PayNym cosmic name. */
    fun mine(ctx: Context): String = cosmic(PaymentCode.myCode(ctx))

    // Keep in exact sync with PaynymName.swift (same words, same order).
    val ADJECTIVES = arrayOf(
        "Astral", "Lunar", "Solar", "Stellar", "Cosmic", "Nebular", "Galactic", "Orbital",
        "Radiant", "Celestial", "Quantum", "Novaborn", "Pulsing", "Ionic", "Photonic", "Zenithal",
        "Auroral", "Meteoric", "Eclipsing", "Gravitic", "Void", "Plasmic", "Spectral", "Luminous",
        "Interstellar", "Nebulous", "Cometary", "Astro", "Haloed", "Twilight", "Umbral", "Solaris",
        "Andromedan", "Orionic", "Vegan", "Polar", "Sidereal", "Empyreal", "Astronomic", "Cryonic",
        "Helios", "Selenic", "Tycho", "Kelvin", "Redshift", "Blueshift", "Parallax", "Zodiac",
        "Perihelion", "Apogee", "Nadir", "Ecliptic", "Galilean", "Keplerian", "Newtonian", "Boreal",
        "Equinox", "Solstice", "Lucent", "Ferrous", "Cobalt", "Argent", "Auric", "Obsidian",
    )

    val NOUNS = arrayOf(
        "Nebula", "Quasar", "Pulsar", "Comet", "Nova", "Vela", "Orion", "Lyra",
        "Draco", "Andromeda", "Cygnus", "Rigel", "Vega", "Antares", "Polaris", "Sirius",
        "Halley", "Titan", "Europa", "Phobos", "Ceres", "Kuiper", "Oort", "Corona",
        "Zenith", "Aquila", "Perseus", "Cassiopeia", "Hydra", "Phoenix", "Lynx", "Corvus",
        "Pegasus", "Aries", "Taurus", "Gemini", "Leo", "Libra", "Scorpius", "Sagittarius",
        "Deneb", "Altair", "Spica", "Capella", "Arcturus", "Procyon", "Betelgeuse", "Bellatrix",
        "Mizar", "Alcor", "Castor", "Pollux", "Fomalhaut", "Achernar", "Canopus", "Regulus",
        "Meridian", "Aphelion", "Singularity", "Horizon", "Photon", "Graviton", "Neutron", "Quark",
    )
}
