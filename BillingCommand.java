package com.saas.billing;

import java.util.Objects;

/**
 * Command / Parameter Object que encapsula os parâmetros necessários para o processamento de cobrança mensal.
 * Elimina os code smells de Long Parameter List e Primitive Obsession.
 */
public record BillingCommand(
    String customerId,
    String planType,
    Money basePrice,
    String signupDateStr,
    int overdueDays,
    String promoCode,
    boolean isNonProfit,
    String paymentCardToken
) {
    public BillingCommand {
        Objects.requireNonNull(customerId, "customerId não pode ser nulo.");
        Objects.requireNonNull(basePrice, "basePrice não pode ser nulo.");
    }

    /**
     * Construtor de conveniência que aceita basePrice como double,
     * convertendo internamente para o Value Object Money.
     */
    public BillingCommand(String customerId, String planType, double basePrice,
                          String signupDateStr, int overdueDays, String promoCode,
                          boolean isNonProfit, String paymentCardToken) {
        this(customerId, planType, Money.of(basePrice), signupDateStr, overdueDays, promoCode, isNonProfit, paymentCardToken);
    }
}
