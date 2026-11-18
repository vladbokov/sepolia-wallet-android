# sepolia-wallet-android
Self-custody Ethereum wallet for Android (Sepolia testnet only). Kotlin, Compose, Hilt, web3j, JSON-RPC client, BIP-39/44, EIP-1559 signing, ERC-20 ABI encoding, Android Keystore + biometrics. Not for real funds.

## Known limitations

- Single account, single network, ETH transfers only. ERC-20 `transfer` encoding exists in `core:wallet` but is not wired into the app: sending tokens needs gas estimation against the token contract and a transfer draft that shows the real recipient and amount from the calldata.
- The RPC endpoint (`-PrpcUrl`, default: the public PublicNode Sepolia node) is compiled into `BuildConfig` and therefore ends up in the APK in plain text. Do not put a private API key there: a client app cannot keep a secret, and a key meant to stay private needs a backend proxy.
