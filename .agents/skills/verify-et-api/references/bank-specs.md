# Bank-Specific Payloads

Use this reference when building validation, normalizing user input, or debugging `422` responses.

## Supported Direct Verification Banks

`POST /api/verify` supports direct verification for:

- `cbe`
- `telebirr`
- `mpesa`
- `dashen`
- `boa`
- `cbebirr`
- `awash`
- `siinqee`

`zemen` may appear in status or enum surfaces, but direct `POST /api/verify` returns unsupported for now.

## Explicit Bank Payload Matrix

| Bank | Required payload | Notes |
| --- | --- | --- |
| `cbe` | `receiptNumber`, CBE receipt link, or `referenceNumber` plus `accountSuffix` | Legacy FT references need `accountSuffix` exactly 8 digits. |
| `telebirr` | `transactionNumber` or `reference` | Use the provider transaction/reference number exactly as shown to the customer. |
| `mpesa` | `transactionNumber` or `reference` | Receipt URLs or SMS text may be parsed when passed as `reference`. |
| `dashen` | `referenceNumber` or `reference` | Universal detection recognizes the current Dashen reference pattern. |
| `boa` | `referenceNumber` or `reference`, plus `accountSuffix` | Suffix must be exactly 5 digits. |
| `cbebirr` | `receiptNumber` or `reference`, plus `phone` or `phoneNumber` | Phone may normalize from `09...` to `251...`; prefer storing normalized `251XXXXXXXXX`. |
| `awash` | `referenceNumber` or `reference` | Full receipt URLs or configured token fragments can be used. |
| `siinqee` | `referenceNumber` or `reference` | Full receipt URLs or configured token fragments can be used. |

## Universal Router Payload

Use universal mode when the user's UI accepts mixed receipts and references:

```json
{
  "reference": "receipt, URL, SMS text, or transaction reference",
  "suffix": "optional CBE/BOA account suffix",
  "phoneNumber": "optional CBE Birr phone disambiguator"
}
```

Detection rules:

- `FT...` references need `suffix`.
  - 8 digits routes CBE.
  - 5 digits routes Bank of Abyssinia.
- Legacy FT composite references can include the suffix in the same value.
- Ten-character alphanumeric references route to Telebirr unless `phoneNumber` routes them to CBE Birr.
- `phoneNumber` accepts normalized `251XXXXXXXXX` or local `09XXXXXXXX`.
- Known M-Pesa receipt URLs or SMS text can yield the transaction number.
- Known CBE, Awash, and Siinqee receipt URL origins can route automatically.
- If universal detection is ambiguous, send explicit `bank`.

## Bank Notes

### CBE

Use a CBE receipt ID/link when available. For legacy FT references, include `accountSuffix` exactly 8 digits. Treat `confirmationHistory.confirmedBefore` seriously in checkout flows because CBE references can be reused in support disputes or duplicate submissions.

### Telebirr

Use `transactionNumber` for explicit payloads. In universal mode, a ten-character alphanumeric reference without a phone disambiguator routes to Telebirr.

### M-Pesa

Use `transactionNumber` for explicit payloads. When the user pastes a supported M-Pesa receipt URL or SMS snippet, pass it as `reference` and allow extraction.

### Dashen

Use `referenceNumber` or `reference`. If universal detection fails, switch to explicit `bank: "dashen"` rather than guessing.

### Bank of Abyssinia

Always collect the 5-digit `accountSuffix` when verifying BOA FT-style references. Without the suffix, the same reference shape can be ambiguous with CBE.

### CBE Birr

Collect both receipt/reference and phone. Normalize phone numbers before storage and display. Use `251XXXXXXXXX` for API payloads when possible.

### Awash and Siinqee

Use full receipt URLs when available. Token-only references should be passed exactly as received from the bank/provider.

## Validation Strategy

- Validate bank-specific required fields before calling Verify.et.
- Normalize whitespace and obvious phone formatting at the user's boundary.
- Keep the original user input for support/audit if policy permits.
- Persist the normalized payload used with each `Idempotency-Key`.
- When validation fails, return a user-actionable message that names the missing disambiguator.

## Related References

- Endpoint contract: `api-endpoints.md`
- Error handling: `error-codes.md`
- Integration choices: `../patterns/integration-methods.md`
