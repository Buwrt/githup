package com.hubmobile.app;

/* 自动生成，不要手改。由 tools/gen-guard.py 用官方私钥生成。 */
final class GuardKeys {
    private GuardKeys() { }

    /** 官方证书指纹（小写十六进制）。改它 → ring2 验签不过。 */
    static final String CERT_SHA256 = "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927";

    /** 官方公钥（SubjectPublicKeyInfo DER 的 base64）。用来验下面这份签名。 */
    static final String PUBKEY_B64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoXqXbnGAemjOqdbv5UFghBhiBUkDiN1/MeN2+F4SlpLKXtot4JR0+UPgZGNSx6CItLtJoJb+gq6CFQHVRGckqQ999FR1U9QUkvLyF6t5Hyd4YHEXrnuvShUnlWFxTFxzZXA3yrVz/sGr1v/Qf7LmhG6etFc87H7BOigo80jrcECKqf6URIevU0Xz5vN7IpxvKo9dG59O9ZfHvqnj42YJhfjbACOvqaQH0+Q1X/I2QlrroFa8RcGQRa0J2cOETTbh5bNE52r8QY5FmXTFjwHP5w3CO6Mkd9dq1itRii6+ku271iSpqKuVAtC3kOVIegzT9/RBlli60ToLUAIlNjkTUOUSMtaSPQzUc2NJ4s/eiR8A5+dYV5piCLssCVrtudryVKBWpFCdIyzFNz4a1t1avd0xUTLjD8OWWnj+RoUr3ZAX80UJiCKNwgDVJrnblGCCDS7I+kcUHE9a+f4hZqHCrUFGt1WBkfqbkEL9qhtY2G1ik8S92hBYHa4GGj+5jQzV6fPSpaEowQiAb2HHyrJaRpZYX/aq5HeDjVlM3P2oxfOuXpljTzrqgvzAc3c8b1AfSgXsRHX6fyJbLbvmJ0li9ZtiknoPJd42ALh5LhGAEUpmLfbEjLnpkuqj3UouKFQOyH/eCRBSisZAuZuFjke8s1iD4WJyWfHeezcjwPDOggkCAwEAAQ==";

    /** 官方私钥对「整条链期望值」的签名。没有私钥就伪造不出来。 */
    static final String CHAIN_SIG_B64 = "EZ5kCe6FXHacLtSH99yc9OgLy6qAYRDlI474OmcR2nk8GysKvcwGbRMuWHq/bpLYt8dz+JRhS+CbbcWP6elTxn5T1g7ao396pax7LRAIj6qfc5Ls5tY46IbjRi5asTL1QoqEDif+/OgtqQaJg1bW1VKyP7mIDbtXl2ZjdpmGwhDJtWa9hDnXRmq6smbLO64PMM5bLkexn8ZHehBK37CD+SbFwvX1T7B5htjPYGB1c1sftOnpm5qt7f3AuzGNHhliN2sY5RfWyo0qX5BVIHq0gV6TCmgt6ZawesePIMI5SP96x9v4JldvgPh2KSojlfsT/g7k10ZtkIANiRCuv3lMBao3Bzcp27obq2lw7fqyXuXdzWa22B3TPDoyDsi+NwAQafUTsNRSfMtv0Ab5N7g9/xNy1KnyFL9lvRFZP0ZLMCdajYQvnN+NH5eXEPnkZZ22ogF3BNEwuzNjQQdai0r9V8XkMoku6ZwcIdER19ekQDyNgk4GyKlJ8P9+miurGx9dT0BlKet0oZha7ktAHeXSmu2jWv//T6QbNRQuw53SD3xGSdvxnHChr9T+SEfnKQaxU35eVpvVgZDXNnMuJpBFeRUqxyn+bHDJsBPnBmLgPnzt7HuJ7S7fwBhqBGs+bz77YKykooRZgezIqwtce3PaCEKyPZMMPP2XJ3jhJpuV6Qc=";

    /** 链的种子（参与每一环 token 推导） */
    static final String SEED = "githup-guard-chain-v1";

    /** 每一环的期望 token，顺序即 1→2→3→4→5 */
    static final String[] CHAIN = new String[] {
        "c9efb2a31cd5605ee61529c1822814233368882d9e0bf4b742de5e718083b08a",
        "9a37082a545da5932711cb34b67ec5ef8b0d2208b23ec7be5861862ac312528f",
        "d89f189cc9ae98487a1c7a30b351943b57eafe00af5f02faab956d2be21545a7",
        "c15a39cd26d624d89d6c73c466aaf9984d140d255d09c687880273b5f845a87f",
        "f744656ef0659452a61f617a5799b5135fe002eae9e4da8cd5355faee693d587",
    };

    static final String PKG = "com.hubmobile.app";
    static final String APP_CLASS = "com.hubmobile.app.App";
    static final String LABEL = "githup";
    static final String VERSION_NAME = "1.1.3";
    static final int VERSION_CODE = 1001003;

    /** 官方下载地址（被认定篡改后，直接把用户送到这里） */
    static final String OFFICIAL_URL =
            "https://github.com/Buwrt/githup/releases/download/v1.1.3/githup-1.1.3.apk";
    static final String OFFICIAL_HOME = "https://github.com/Buwrt/githup";
}
