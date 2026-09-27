package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "FlTDE8QBthCGrO9VS7jmVSzACfEzjZh3UIvzmqU7bMoBDqa66HXOJ4wp7lCnGaYxjHxGP7ftiBZaXH7y25+u4Mm53md2miJTEMNsqS1fWQuU4g022MKNiYf6akPs58I+abk1EJhCPSAGEQ0NgCXobeTb50R7YOjAE6NOa0zLi5AstNJyLJ/XaoXaBvPgJlNHaAjtM9kUL562GtW1am9j47yv895+Wft9eql1JVW12Y0PpoduOazr+mgDGhj/73YGkGI8LsorTgBwgoxWhfAdvy8CJZ7c8NsP4aUSl4F4HajJdlaXujD2z/jVJvqePPmR6uj4GT6CKRC5J8/unmCjlPcb4PUY/fL/5n2xBmzT08tSN/w6bGMX8DB1CD3cQAggvuRdJ7evnhqLjQuQ+F6Kw4UIT6edlcacYvOY34Lt7caBhdIYdAvR0fS/2udCqiZU/8gVnyt4UfDpU69LcbHiMF/guP3RegWuZIqdeKQx+xIvpA6LJ7u+pR+nC+hCNZuIpRmmvaXKgclBvy+xvpHx77yygEBNBXjSyUQH8TVcWHvkizRkTP01S2Ktz/dza6WzDF9bONYn4kY/uC7mmnWgr8EKTcoY5wdNQSr0lL3iiwrV491/3LYMpplQ5ZjyvscWixnE/KG8Jh/y/3GULd9o2CRO9ww5Q+62J9Tm9HDTvNY=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "03d08c04fd2d2054237cd67d1b8a8a43ddb0bfdd370d670edb1bc5ceacf9592a",
        "5cc3fdb6650e475558be6f2a55d77d879782d416cbb2fef2c6068d9b3a511828",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.14";
    static final int VERSION_CODE = 1002014;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.14/githup-1.2.14.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
