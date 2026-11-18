package com.squaretoast.payment.adapter.stripe;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.payment.ChargeRequest;
import com.squaretoast.domain.payment.GatewayException;
import com.squaretoast.domain.payment.GatewayIntent;
import com.squaretoast.domain.payment.PaymentGatewayPort;
import java.io.IOException;
import org.springframework.http.client.ClientHttpResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe Connect destination charges (plan §4): the merchant's connected account is both {@code on_behalf_of}
 * (settlement merchant, statement descriptor) and {@code transfer_data[destination]}; the platform keeps
 * {@code application_fee_amount}. Card data never reaches this class: Terminal or the hosted page collects it.
 */
public final class StripeConnectAdapter implements PaymentGatewayPort {

    /** Pinned so a dashboard upgrade cannot change request or webhook shapes underneath us. */
    public static final String API_VERSION = "2025-09-30.clover";

    private static final Pattern BALANCE_TXN = Pattern.compile("txn_[A-Za-z0-9]+");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StripeApi api;

    public StripeConnectAdapter(String apiBase, String secretKey) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(TIMEOUT);
        RestClient client = RestClient.builder()
                .baseUrl(apiBase)
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .defaultHeader("Stripe-Version", API_VERSION)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new GatewayException(errorCode(response), response.getStatusCode().is5xxServerError());
                })
                .build();
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(StripeApi.class);
    }

    @Override
    public GatewayIntent createCardPresentIntent(ChargeRequest request) {
        MultiValueMap<String, String> form = destinationCharge(request);
        form.add("payment_method_types[]", "card_present");
        return create(request, form);
    }

    @Override
    public GatewayIntent createCardNotPresentIntent(ChargeRequest request) {
        MultiValueMap<String, String> form = destinationCharge(request);
        form.add("automatic_payment_methods[enabled]", "true");
        return create(request, form);
    }

    @Override
    public Money processorFee(String balanceTransactionRef) {
        if (!BALANCE_TXN.matcher(balanceTransactionRef).matches()) {
            throw new IllegalArgumentException("not a balance transaction id");
        }
        StripeApi.BalanceTransaction txn = call(() -> api.balanceTransaction(balanceTransactionRef));
        return Money.ofCents(txn.fee(), CurrencyCode.of(txn.currency().toUpperCase(Locale.ROOT)));
    }

    private GatewayIntent create(ChargeRequest request, MultiValueMap<String, String> form) {
        StripeApi.PaymentIntent pi = call(() -> api.createPaymentIntent(request.idempotencyKey().toString(), form));
        return new GatewayIntent(pi.id(), pi.clientSecret());
    }

    private static MultiValueMap<String, String> destinationCharge(ChargeRequest r) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Long.toString(r.amount().cents()));
        form.add("currency", r.amount().currency().code().toLowerCase(Locale.ROOT));
        form.add("application_fee_amount", Long.toString(r.applicationFee().cents()));
        form.add("on_behalf_of", r.connectedAccountRef());
        form.add("transfer_data[destination]", r.connectedAccountRef());
        form.add("metadata[check_id]", r.checkId().toString());
        form.add("metadata[tip_cents]", Long.toString(r.tip().cents()));
        return form;
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new GatewayException("network_error", true);
        }
    }

    /** Only Stripe's machine-readable code; its message may echo request details. */
    private static String errorCode(ClientHttpResponse response) {
        try {
            JsonNode error = JSON.readTree(response.getBody()).path("error");
            String code = error.path("code").asString("");
            return !code.isEmpty() ? code : error.path("type").asString("unknown_error");
        } catch (IOException | JacksonException e) {
            return "unknown_error";
        }
    }
}
