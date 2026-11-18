package com.squaretoast.config;

import com.squaretoast.domain.payment.PaymentGatewayPort;
import com.squaretoast.payment.adapter.stripe.StripeConnectAdapter;
import com.squaretoast.payment.adapter.stripe.StripeWebhookVerifier;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Stripe adapter. Secrets come from the environment only; with none set, no webhook verifies. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentConfig.StripeProperties.class)
class PaymentConfig {

    @ConfigurationProperties("squaretoast.stripe")
    record StripeProperties(@DefaultValue("https://api.stripe.com") String apiBase,
                            @DefaultValue("") String secretKey,
                            @DefaultValue List<String> webhookSecrets) {

        @Override
        public String toString() {
            return "StripeProperties[apiBase=" + apiBase + "]";
        }
    }

    @Bean
    PaymentGatewayPort paymentGateway(StripeProperties stripe) {
        return new StripeConnectAdapter(stripe.apiBase(), stripe.secretKey());
    }

    @Bean
    StripeWebhookVerifier stripeWebhookVerifier(StripeProperties stripe) {
        return new StripeWebhookVerifier(stripe.webhookSecrets(), Clock.systemUTC());
    }
}
