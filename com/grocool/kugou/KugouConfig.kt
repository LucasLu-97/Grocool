package com.grocool.kugou

/**
 * Platform constants of the KuGou Music API, mirrored from KuGouMusicApi's
 * `util/config.json`, `util/helper.js` and `util/crypto.js`.
 *
 * `LITE` is KuGou Music Concept (概念版). MAIN and LITE tokens are NOT
 * interchangeable — the upstream README states so explicitly — so the platform
 * is fixed at client construction time and never switched afterwards.
 */
enum class KugouPlatform { MAIN, LITE }

data class KugouConfig(
    val platform: KugouPlatform = KugouPlatform.LITE,
) {
    val isLite: Boolean get() = platform == KugouPlatform.LITE

    /** config.json: appid / liteAppid */
    val appid: Int get() = if (isLite) LITE_APPID else APPID

    /** config.json: clientver / liteClientver */
    val clientver: Int get() = if (isLite) LITE_CLIENTVER else CLIENTVER

    /** config.json: srcappid */
    val srcappid: Int get() = SRCAPPID

    /**
     * helper.js `signatureAndroidParams` salt — shared with `signParamsKey`
     * and the cloudlist appkey (CLOUDLIST_CONF in request.js).
     */
    val androidSignSalt: String get() = if (isLite) LITE_SALT else ANDROID_SALT

    /** helper.js `signKey` salt. */
    val signKeySalt: String get() = if (isLite) LITE_SIGNKEY_SALT else SIGNKEY_SALT

    val rsaPublicKeyPem: String get() = if (isLite) LITE_PUBLIC_KEY_PEM else MAIN_PUBLIC_KEY_PEM

    companion object {
        const val APPID = 1005
        const val CLIENTVER = 20489
        const val LITE_APPID = 3116
        const val LITE_CLIENTVER = 11440
        const val SRCAPPID = 2919

        // helper.js salts — typo-free copies, do not "fix" them.
        const val ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
        const val LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        const val REGISTER_SALT = "1014"
        const val SIGN_SALT = "R6snCXJgbCaj9WFRJKefTMIFp0ey6Gza"
        const val SIGNKEY_SALT = "57ae12eb6890223e355ccfcb74edf70d"
        const val LITE_SIGNKEY_SALT = "185672dd44712f60bb1736df5a377e82"
        const val CLOUD_SALT = "ebd1ac3134c880bda6a2194537843caa0162e2e7"

        // request.js headers
        const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
        const val KG_THASH = "5d816a0"
        const val KG_RF = "B9EDA08A64250DEFFBCADDEE00F8F25F"
        const val KG_RC = "1"
        const val KG_REC = "1"

        const val MAIN_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB
-----END PUBLIC KEY-----"""

        const val LITE_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB
-----END PUBLIC KEY-----"""

        /** login_token.js fixed AES key/iv pairs, per platform. */
        const val LOGIN_TOKEN_KEY = "90b8382a1bb4ccdcf063102053fd75b8"
        const val LOGIN_TOKEN_IV = "f063102053fd75b8"
        const val LOGIN_TOKEN_LITE_KEY = "c24f74ca2820225badc01946dba4fdf7"
        const val LOGIN_TOKEN_LITE_IV = "adc01946dba4fdf7"
        const val LOGIN_TOKEN_LITE_T2_KEY = "fd14b35e3f81af3817a20ae7adae7020"
        const val LOGIN_TOKEN_LITE_T2_IV = "17a20ae7adae7020"
        const val LOGIN_TOKEN_LITE_T1_KEY = "5e4ef500e9597fe004bd09a46d8add98"
        const val LOGIN_TOKEN_LITE_T1_IV = "04bd09a46d8add98"
    }
}
