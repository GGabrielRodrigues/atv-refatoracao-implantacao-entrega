package com.saas.billing;

import java.time.LocalDate;
import java.util.Optional;

public interface CustomerRepository {

    Optional<Customer> findById(String customerId);

    void saveInvoice(String customerId, Money amount, String status, LocalDate createdAt);

    record Customer(String email, String status) {
    }
}
