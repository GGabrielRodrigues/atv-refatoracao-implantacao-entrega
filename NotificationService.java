package com.saas.billing;

public interface NotificationService {

    void sendInvoice(String email, Money amount);
}
