Integrate verify.et for online payment verification for MANUAL_TRANSFER based on the skills in .agents/skills/verify-et-api

banks in verify-et-api correspond to method_code in payment_methods table.

The flow should be like below:

1. User tap buy button in frontend. Assume user is in Ethiopia.
2. Frontend request backend for payment_channels from payment_methods table.
3. Backend returns payment_channels to frontend.
4. Frontend shows payment_channels to user.
5. User select payment_channel: ONLINE_PAYMENT or MANUAL_TRANSFER
6. Assume user selected MANUAL_TRANSFER
7. Frontend request backend for payment methods where payment_channel = MANUAL_TRANSFER and is_active = true and country_code = ETH and platform = ANDROID/iOS/WEB
8. Backend returns payment methods to frontend.
9. Frontend shows payment methods to user.
10. User select payment method. Upon selection, frontend show payment_instructions to user, which is from payment_instructions column of payment_methods table.
11. Below the payment_instructions, frontend shows a form to user to enter payment details. This form is rendered based oon the verification_params column of payment_methods table.
12. frontend send payment details to backend.
13. backend verify payment details using verify.et api.
14. Backend update order status based on the response from verify.et api.
15. Backend use the immediate response, if verify.et send it, otherwise (if queued by verify.et,) backend use webhook to get the response.
16. Frontend polls backend for order status.
17. Backend returns order status to frontend for the polls

Do not change the flow of revenueCat, chapa, etc. The above flow is for MANUAL_TRANSFER based online payment verification.
Remove the previous unnecessary endpoints, code and comments. 
