package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "Cs15zVz+QEKoOHZNLq0nWAIzJOJbECUoCuV8znXiBPHPBthtJR3XBF2D1kwbjD+w2fLbDBwEKabsmD5+7/Bdb3bLKcmLw1awMXvCeR3uY5SwJVMI6ZlIHA4L3eaul2EN4J6fxMRIL3pqirnlUGJ6K/n78EWMvb1GPk8b3df6D3ZK5rFrnzrgwo8MJSpFY6rLkdnVrI18m8ROqwOsEG4gq+h0enMOP2Ujy8bTdDz2u+inuZ8dMo+AMCxw6srkT+Cbd5HM0uA7ugcdotp52+M0peI4yOsUsUqkbLWQO9fDBrpVlmb1A9GF6N5opq7XuCaOhEAP+PZrBxj0pxc7xl23wavHsR4fzYGg9JePmN7vPvuSroXeffmul7Dbwp4GbBwSubKbh0keYzwV9Ghs/yeKcCu9FCu8enH57fMREwot8P2o9S0LbXTjo4n0UdSw74MeK+g3YeXcUj4bZfpiJ1nWGWhVsx6vahAP+qutAbRmOOrvUl3DCHRWzpe6j5OLjTQzQJtoK0Vc4bLNlMllwOLztuYFFBg97s2CH/13aME5mS23km8jJn/4JbgKPz2iu3JdlvxeLKk5Xud81aPLG5TZGSMyggogOiLyXF5Wt2KFW4P8IzxhHpk4H7hYdS06RxSPEBUfEH1RbF16hdJEo/uukV4Urow7P6lcQKsEf6r0MEQ=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "58f116257d149a6c260234d3e619f21b9dc53e05a00032a746d87baace5a3250",
        "41f6051c1bd08a868020cc966b5c46c09a0f31a1d9381772d025792919ad18c2",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.3";
    static final int VERSION_CODE = 1002003;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.3/githup-1.2.3.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
