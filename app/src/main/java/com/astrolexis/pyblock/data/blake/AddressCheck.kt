package com.astrolexis.pyblock.data.blake

import com.astrolexis.pyblock.R
import org.bitcoindevkit.Address
import org.bitcoindevkit.Network

/**
 * Is this string an address that can actually receive coins — and if not, WHY not.
 *
 * Every field in the app used to check the shape: starts with 1/3/bc1, length in range. A typo that
 * keeps the length passes that test, and a rig name like `HS-BOX-net4` fails it for the right reason
 * but with the wrong message. Neither helps the person about to point a miner at it for a week. This
 * checks the real thing — Base58Check / bech32 checksums, through the same parser the spend path
 * uses to build the output — and says what is wrong in words a miner can act on.
 *
 * On this pool the payout address IS the stratum username, so an address nobody validated is a week
 * of mining that pays nobody. Mirrors iOS `AddressCheck`.
 */
object AddressCheck {

    /** Strip what people paste around an address: URI scheme, query, whitespace. */
    fun normalize(raw: String): String {
        var s = raw.trim()
        for (p in listOf("bitcoin:", "BITCOIN:", "bitcoin://", "BITCOIN://")) if (s.startsWith(p)) s = s.removePrefix(p)
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        return s.trim()
    }

    /** The payout part of a stratum username (`addr.rig1` → `addr`). */
    fun payoutPart(raw: String): String = normalize(raw).substringBefore('.')

    /** Mainnet address that can receive a payment. Checksum-verified, not shape-guessed. */
    fun isValid(raw: String): Boolean {
        val s = normalize(raw)
        if (s.isEmpty()) return false
        return runCatching { Address(s, Network.BITCOIN) }.isSuccess
    }

    /** Same, tolerating a `.worker` suffix — for stratum usernames. */
    fun isValidUsername(raw: String): Boolean = isValid(payoutPart(raw))

    /** Why it can't be used, in one sentence, or null when it's fine. */
    fun problem(raw: String): String? {
        val s = normalize(raw)
        if (s.isEmpty()) return null                 // nothing typed yet isn't an error
        if (isValid(s)) return null
        val lower = s.lowercase()

        // Another network's address: valid checksum, wrong chain — the coins would be unspendable.
        if (runCatching { Address(s, Network.TESTNET) }.isSuccess) {
            return com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_that_s_a_testnet_address_this_chain_need)
        }
        if (s.contains(' ')) return com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_that_has_a_space_in_it_an_address_never_)
        if (!(lower.startsWith("bc1") || s.startsWith("1") || s.startsWith("3"))) {
            // The rig-name case: this is what leaves miners at 0 sats for days.
            return com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_that_isn_t_an_address_it_looks_like_a_ma)
        }
        if (s.length < 26) return com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_too_short_for_an_address_something_got_c)
        if (lower.startsWith("bc1") && s.length > 62) return "Too long for an address — something got pasted twice."
        if (!lower.startsWith("bc1") && s.length > 35) return "Too long for an address — something got pasted twice."
        // Right shape, wrong checksum: one wrong character.
        return com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_the_checksum_doesn_t_match_one_character)
    }

    /** The address in groups of four, so a human can compare it with the one they meant. */
    fun grouped(raw: String): String {
        val s = normalize(raw)
        if (s.length <= 8) return s
        return s.chunked(4).joinToString(" ")
    }
}
