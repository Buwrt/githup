package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "Kgs5gKP9ti9TkEt9+PBzIAlgu84gGbN19VRUjIo3Yaw0H8A/pQTpYEoLv0dO6PUbhfS5GHG9/YRHwXjrHZy7A0LfU7VSfNmAN0LvWJsqiDbLW+frghL2jzVb8xG4r3Gss6KVsq0Gja2nre0WcBe/dfXEVym+ftn4Qw45jBsT18e2KLBghtfq2I6ZiEi3/UOyC9o5gM0pWvxenb9okNk8gOBNV/lszs9VI1xfTOylnDbCxA/0Kr6Hi1mvCArWNBGL+l0ycMh5iHH6L6KE33+0VOWv6DNEyweqxZKjQW2NaYZLm2A+nbq975Hu5Wgigm3+MicY4RLyGpqCP8exRCO9Peke/Mq550eh6XTy/bhhk3FH5xUIwgQFxjMkc4oQv2gIxcHQ6X5xI8zjoQ75iT2w5Ul3EUIyC+yv9fsqckmwjzQXB3VaZhvC4DAQmQ+zBsytizH72uBJcEA5EJx0L/1dxPWOnFO/VjSIp89TQE5Q2DJeD/ukiBIkZSfdw2SwG/cSpUBpk42DtQgtSAxpfpgSegJwQICZGKYv5OWJccihHrARVwrGiqXh/V2BQbHStkmwXSv0o1cpaFFRdKnLNNckcHS0oqNU+comXElimjxiXyDsYtUgB6HYbTLk7JQkrQEQYSSggGZe8ijd/VrW1KZbT9QQsEMioN+T5wyYZKPA4fA=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "648f10ebb391c8d5826c6d318bb82ea71c3014a5dc4404d4359423a6eb834399",
        "bec4189a47d67cef078db62be19d57d16906b3fe030a996d9f053954a8a382a3",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.2.17";
    static final int VERSION_CODE = 1002017;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.2.17/githup-1.2.17.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
