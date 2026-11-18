package com.squaretoast.payment.adapter.stripe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** The few Stripe endpoints SquareToast calls. Requests are form-encoded, responses JSON. */
@HttpExchange(url = "/v1", accept = MediaType.APPLICATION_JSON_VALUE)
interface StripeApi {

    @PostExchange(url = "/payment_intents", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    PaymentIntent createPaymentIntent(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @RequestBody MultiValueMap<String, String> form);

    @PostExchange(url = "/terminal/connection_tokens", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ConnectionToken createConnectionToken(@RequestBody MultiValueMap<String, String> form);

    @GetExchange("/balance_transactions/{id}")
    BalanceTransaction balanceTransaction(@PathVariable String id);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentIntent(String id, @JsonProperty("client_secret") @Nullable String clientSecret) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceTransaction(String id, long fee, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConnectionToken(String secret) {}
}
