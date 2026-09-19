package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "lbhZ5XNt1H+3qkwAtA47Rrdev4udqTgrhuEj6dTYN+IKcIYnbQX3jeub6Gig9XfBIuLrE0QkLTfjty6Z1P2mAVq8lFHjjc9GecDKSx7AhnYxFpMSG3mj7z+wKyYzLZnPyVKhUkJVVqmYtX23acMGN9qCbvSH0zn3g6rBbLxOgKC/6NsTg1WXba0MDEKZMsIIjLr8eoyKDDih+4oYgJRgUR9I6Bnxy36VyA8o+dd6F3Kz27UsF6YuA+L7Af7jpOHVjeCuKmSBGd1Srl4DvOEkTxDvnZul08k82UtILHIqRWLhrpJM1Mr4ptlGMhtuJ+d6aSGSSTVehS1I/pnej5MaxK2IAAu+aQjIVZiJ9FFeQZovuBuh9dGh8co3YsZNdu4MP//yhkDiShWBQaJXEazUa7yQ1hA3+KGuxUA1A0V7pVzqHXxZBR8Rsh0prGzPK/X8D7jZOdHTXCQ4yS+5Hq25ZCLBBwhA1Rx1i5iriQAIv4SmpCCA5V/8ITFioXa7D1Nu5Hj4dWcY0yg2Nl6UAXSg3BsnttGvfJaPLPdZX62XUeaJ20HT4VYjEu187n84Z9p1wDHzozHAWKfeybwe+yXwgQwZyoVwNh8zzPzuw1dh6ATXY8HMNBfMZUkNas5wB2AXoqClQyD7Ua7xVonIScOl0M3kumhQ5ReiDQ8E1zb3nE0=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "4a5c06c4a2c741ced8385a42a16a5ef2b7ca583e7cf2ed3fc3716ca24b02c19f",
        "47a4a5f7d46110d1bad7b4b0a95f9715fc39ddc8ac6149b4a4a95d9c571d194c",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.1.4";
    static final int VERSION_CODE = 1001004;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.1.3/githup-1.1.3.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
