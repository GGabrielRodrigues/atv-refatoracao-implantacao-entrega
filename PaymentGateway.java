package com.saas.billing;

public interface PaymentGateway {

    boolean processPayment(String paymentCardToken, Money amount);
}
