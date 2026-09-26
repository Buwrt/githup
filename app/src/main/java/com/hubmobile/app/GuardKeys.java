package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "XV/mHfYqWMwC8WLgGwZ6W0gNszUd0jTmyV7DfxCjgNN7sqLX7W3Vu1oh/fBRQ7FKz5aL5YwSRdUjVIAegydvubvkqcc+fEIX/RWp6uTFUhoJOVim6YV2TfiYJCFQiiXx7aC0vp1HIiFMV0s0vxTugVqXdlpRiaKNT/R5t+6DkMSJis9bAOL53JB9vri6KZqw4sAbvTYW5Fo6/pt94lcV5NNRkvwAHTgjBK4Y9/3zDrscbNCQSyO779zJEQWOe6lrDPTPlEQycw0mnLXOG2869Klde9ekQL5p5A2t82ImXnTdqhwLlqQ9a0MnLYY6srstZiXBRq1tyWa5PDECEWKmfIUDUsnBDggos07hnkV8WxtNJJcAKg6DnhroN2QQDKk4KgRxOsLZYvLGqnO+P8U6izURAMBfgpmCKqPU0b7quaY3sJyueHBjk7kPVD+ReVk9iHmK8uKdOLnF0Y+mZIrOLWyAUVK5KQPmB6cnkNU9ctRCWZMCvItnPruu9sH9bChxz2aDK1kX9v8uR8N3OKcNxcq6tLetMltSKbCrO9xZ9uopDBB6sK/KXVXJGZOTDf0LEaFAMvp/G5ehVcTPkS/7DJ44NzishpDrE4kqp7Li9aMJ+hPxLImhNEx7MesfcIVIvOnX36qtp7Htk/trL+LHDke4qZ7MwLjuC8wjDDopNPE=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "4ef046f8e08bcb9354e46061e0c5ca58035e18963d886b638d1f15a12dadb2d3",
        "3b590344f12d6e7f39a704fb2e49421565dfdecb62bc52702e1faec825cbb605",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.10";
    static final int VERSION_CODE = 1002010;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.10/githup-1.2.10.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
