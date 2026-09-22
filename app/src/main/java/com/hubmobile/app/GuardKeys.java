package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "Aw2XBzD+vVZ9Lbeqvf3OvFp7QE90QmUOFHdk2F4GB7sMLTc2aC4hdhbTVY98rQ6p2D4INWpIIvStoHuW55cQesUYlbumHLur/ixE6k6jA3CS7ujwOA3SAVtQ1rAYeC9l93R9a3L7w4NK7jJOd3YU9MsX8ubSzhQPBqxoC2PGpTcfAziE6u5hJIHQ0S/sQg1LxgNNwFYLuW6iXWE0ylS0rjVqKdibkcQ5/5/EbtKMerqvJ8e9kLnxEF8w3zmbluSS4bRwYE4lVKcrfnfYpE2SkQ5+6IXqg0c6cO7HZvPthHZh90AAdos5kuNAqljPsS+dQxDBiRelMvUXFoS5beevvoHuBe4vkUOsl5Sv5xMo1XQqO5Si2shihchtqxHJlAo2AwfsMnwETZIuJSFWnK4oaaZjXkp8DXkLEGs7+BNUyjh9iDm9s56pZCcI9siLDSkc4vJnqgkNoytLAWo5IB4AKcUL2bgCAUCtMAO3356Q49E3Luj+e7in6vS74oRlQqeJCOgkeaTMfOl0bFIlc+DCKY+GiTIDwZePyj7mGWByaUOnl3sP7XUEDQV2R2b1xfIz0Gta/0b2O+1mUEU6FGVa5g24UdG03qYw7PoDA/5mvb6xaCcM6N/QBxpKrp8zvxTcCZZCJrZcrK1MGTNGm5xnUy9bYBUtaWh86tFGfGTulCo=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "7726480f76f4432cb4bf751285f03631a0d6274de139fb748ee4c7b08ae109db",
        "baf2d0923c0ec22914d9c73bdd3245b8e9d6b39e7323b8b3d2b84f38a5248fe4",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.1.6";
    static final int VERSION_CODE = 1001006;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.1.6/githup-1.1.6.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
