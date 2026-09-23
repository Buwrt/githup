package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "IbLVUlLJYmSqJQ76Jv9WoHCYr1IFq6VVjc1+QtKNnCKfykVrWfiAI75BLJyGnJ01o8fU9mhOaXD1v5C9E1dTQ1RQc6bTBMPQgoaDu8TwWPVcIpZznCsI3Iep0t8JI0NSaE3E+DxK9V99I9Gw5qu5DFCe7/o1z5PYjCMoIey9VEgxIN1Si9XfaAzLtCko+oRms/BI8S28Aqm5kpKU3/xQhHKvd4D0sdYvFhkoISLWLadGnbllUm+oAxdXH1JRaKrpSQ3IAe7GWPYRuAvvWZr1xP3cPpUjY93CW+OxGPpjHaABdv9p11mqKEwdL+dBPmwXdNMLL9MywttSgOqjKSfnneUHvz5sLEC9AEFw4h34rCqocQnlda/TTktmLmEy0nUH0cd+aET2oYU285VSxZMJdzM15c9PcSgfzLtVCRauQ5sPX8LErTNfg+1reTqYwlmYuYTA3GmVlXmQRBADCrug1ye08K24QoLi1FUaDlO32rssNWk6U0EBGohycZ5fR1VTR2z3CGbNE4e0CLkmjMuRYugV/vlY2soe9wXt8d4OvDCW1z1ktt7oU+qklaFLBoRx0rrfuiPoPVpJA/9X/TicAgIMz20/lL4oZxAfwrWBQkfmAmadU0xxwbgD5NvU9Pyq4XqgIn/gJssfsR7zvAeXn2ANvD8SXtbjpZxQoGxwYFA=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "b8a95ab40afe93d73fb9d73685d9a3cf3023f77d49e928a32ac26e78deddaf02",
        "12cea6137ae5aca5c744b14e54d7e2d69b53a863e6a09e6ba4b7b69ce4a21862",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.4";
    static final int VERSION_CODE = 1002004;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.4/githup-1.2.4.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
