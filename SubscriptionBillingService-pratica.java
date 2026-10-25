package com.saas.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SubscriptionBillingService {

    private final CustomerRepository customerRepository;
    private final PaymentGateway paymentGateway;
    private final NotificationService notificationService;
    private final Clock clock;
    private final PricingCalculator pricingCalculator;

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      Clock clock) {
        this(customerRepository, paymentGateway, notificationService, clock, new PricingCalculator());
    }

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      Clock clock,
                                      PricingCalculator pricingCalculator) {
        this.customerRepository = customerRepository;
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
        this.clock = clock;
        this.pricingCalculator = pricingCalculator;
    }

    public List<String> processMonthlyBilling(BillingCommand command) {
        List<String> logs = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        Money calculatedPrice = pricingCalculator.calculate(command, today);
        processCustomerBilling(command, calculatedPrice, today, logs);
        return logs;
    }

    private void processCustomerBilling(BillingCommand command, Money calculatedPrice,
                                        LocalDate today, List<String> logs) {
        Optional<CustomerRepository.Customer> customer =
            customerRepository.findById(command.customerId());

        if (customer.isEmpty()) {
            return;
        }

        CustomerRepository.Customer customerData = customer.get();
        if (!"ACTIVE".equalsIgnoreCase(customerData.status())) {
            logs.add("Cliente inativo. Cobrança cancelada: " + command.customerId());
            return;
        }

        boolean paymentSuccess = paymentGateway.processPayment(
            command.paymentCardToken(), calculatedPrice
        );
        addPaymentLog(command, calculatedPrice, paymentSuccess, logs);
        customerRepository.saveInvoice(
            command.customerId(),
            calculatedPrice,
            paymentSuccess ? "PAID" : "FAILED",
            today
        );
        notificationService.sendInvoice(customerData.email(), calculatedPrice);
        logs.add("E-mail disparado para: " + customerData.email());
    }

    private void addPaymentLog(BillingCommand command, Money calculatedPrice,
                               boolean paymentSuccess, List<String> logs) {
        if (paymentSuccess) {
            logs.add("Pagamento processado com sucesso: R$ " + calculatedPrice);
        } else {
            logs.add("Falha no pagamento do cartão para: " + command.customerId());
        }
    }

    @Deprecated
    public List<String> processMonthlyBilling(String customerId, String planType, double basePrice, 
                                             String signupDateStr, int overdueDays, String promoCode, 
                                             boolean isNonProfit, String paymentCardToken) {
        return processMonthlyBilling(new BillingCommand(
            customerId, planType, Money.of(basePrice), signupDateStr, overdueDays, promoCode, isNonProfit, paymentCardToken
        ));
    }
}
