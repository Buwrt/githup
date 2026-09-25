package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "ev5xSsakLjo5eexyyOWnl8Xs6P4t7BKRTDx+c7Zxpz74zXO6tDhLVK0vOdST5MCvOLcewkV6/usB36EJUeCrT8556XbosbbeGAjV45zNmX7I1TMSqpfmnNk7TRktaNR7DB89JuS/EUloqVV6gFSuyrvCnpWAdnpCI/7/kNErHWnfI63cs1fgPMOuplqL+IyvYqo9g0jgOEh3JM65JgTuDq9kb2N+jSy1QVTj1poOiI69G2GUF2T/qvGIDaGz5GHPS6kY/JICy6pvdMgXJni60fX85QeYZFcvivTDcgpltkVlG20nI1OeSb0l7JxnaDMbMu5yeJ0oTT2hVxYuT/MpkJuk14jAi8yAW7KqGn8EQLiBaPrX8AVzb9dzca7of1p7hzCgpcUTn6ROipIoY2fYVPY92sqZTM8+o6X0NObqTEoYloNTmIH40OUuzfKcSyn2+gRuY7K8knwChaM97AaMVoqolIfJgw5i/J/FBOeuzWvP2tddXrGD7hSSLqmwDzbSC9IOstfUxBSiAODF5b36uYhz1RbHk8z6YiYoOnpts5YWpV0NYkL3mfSkpr4BKn9wvRECNTslv1sNDFS14j1E9RFOZZJp7nAvNG+OUzPYmSN3lW1qfMQx83bBGqXRrsfCdgRcmR9J3LPlglVip/G1dT5khiWpb+cwQDRRc9jGdaU=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "09711f8e5f985889152ea6e6b6a7de48ce205f4387e3368b7ee62832afdf3a48",
        "46b1733cb42ae13ab73a1eb13ecd3ee4432fb5c53a512d96abff2a2da9ca66b8",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.7";
    static final int VERSION_CODE = 1002007;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.7/githup-1.2.7.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
