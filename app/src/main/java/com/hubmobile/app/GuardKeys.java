package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "ea6INRwA63LHnX1x9Di1GA8pj6k1q8PkMfIhejC6CAH7JTdJMADhsgXg76CAyOYwgTRZ9m16xpKsf2EaM44Y7FZ93vM8nIprk86tAWwTDY3rGVRcEiBAu1rqcbcr+on9om12kpGSVXjkxT7CQM8SUJDNIgaNSa32p1q/XwVDpEFVyGhXFQAYDrEY4g8aSsT1KkYL2r59YylmiiAk6fg2n0+xZIB3MhadODAuDRALlYhOmhiAcah3453bS362eaKYvbLeCHq1gKy4v55uy4kvzVDLCVikgtGW7rnKxD0w4tppEfKzXh68+i4hr9ZAbXGB8Ll4Rff8GlqmahaQSjgfDOl5/Jw8wZAxqum9b2jy1LkDfIq2R4ELuni6NgoWfAt1ylQnhLjlBTqFjsJnTLbqj2UWfvHox/mH7R8Ldm1K6WjWzwSc/MCuVXLfCqDkHYLgftOl8BS1xjppLb9KfxLZi5LlRPouNr+PiJJmAj+5iMV/9RLNj0mqlwz498M4Rr3bWbLUliSdY7ygpl+YmO8AB6UgibkSZjj/sKZur4FAkucWCmVrthT9HjdLJ+Q5rVqYyG6vger4ds+85I7gDM8LKRiaWqkkRl4apgrqMaTFo9nuZDxgAqi44YZ2BoWYVJhBnyiCsxKP1ae/jt5bjMMXm/Kxeq6tTd1Mz63tdCCPTfw=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "a0801a958f404c447820fc00e1fb2970024bc2ad30f99c6bde383a02b2b56ee4",
        "fd42a071c02b5084ec83aa3ace015c831c4dfa085e44cb25459e800ddd4f6895",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.11";
    static final int VERSION_CODE = 1002011;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.11/githup-1.2.11.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
