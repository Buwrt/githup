package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "RGkcSLDj5RiH4Qwj/+xseo0DXA2Sk70bknKutOeOU5ioFqwGpHVfl+ozeNnMvvHrcQiqynphrvmQzg1lUzqT6Syajo2ZlO8hiwTIis+BfMlm0p1sI9lan50bKm6/kUf9zeZJwktIcXOg4xHIaMvjdHJrGD3DDnb7xMZrjjuyx47mBzQftsD3Xvs7NCZvqYK1IcXsIypbYzTMJoI536nnrLcryaZCH29s+xWxWOWqfEnD1Quq0ZK9SlGkPscqqWThgJN3lbxhpBleV/Hgow0qkXg5UrHt0SAVqizBGoKoi1b/kYLyCggJTt7GK3aAw9A567AV/dLRD00e+3v9R7fYaGSw435jfMIYXOGrrrqi8BpyPeEXYhooCpYcXGhJy1cX7qstY5zECTsuBq7M8GasMJ4SvOq2H6+onu9NZZG9ufQlBYVwzVh4Dt0V9jhD81QI+UuHOT1lWreLQlQ3dxctBQsHgnc92S4DVG2gUXF8HVPMD8c9mZakywrMgBzyqaT74e/dSyfTr7+sWOqRcfr/7GyQcAf0m1QxsQWh2vfOSkEQ72mSvl/sKoWiPZ66gIbxWbKm+e6fCgUtyG6XMjLYuz9k3rzULg4KMmyk8Lqi2XK58xDYHwNoVmXdO39V+475/erXKh5wYENQ/loKg/li74jMQN9HLBR+3zWduYc5lFU=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "05d31b5508d96c9aaacd60ac3479276efc1e90c24363bac89648f2d627081848",
        "00520ec738afb5c4b00bd8d6e5e8b75a0586a9fecdf8b50b4d772337906ea1c4",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.12";
    static final int VERSION_CODE = 1002012;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.12/githup-1.2.12.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
