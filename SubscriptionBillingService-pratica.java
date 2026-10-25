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

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      Clock clock) {
        this.customerRepository = customerRepository;
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public List<String> processMonthlyBilling(BillingCommand command) {

        List<String> logs = new ArrayList<>();
        
        LocalDate signupDate = LocalDate.parse(command.signupDateStr());
        LocalDate today = LocalDate.now(clock);

        int monthsActive = (today.getYear() - signupDate.getYear()) * 12 + (today.getMonthValue() - signupDate.getMonthValue());

        Money calculatedPrice = command.basePrice();

        if (monthsActive > 24) {
            calculatedPrice = calculatedPrice.subtract(command.basePrice().percentage(15)); // 15% após 2 anos
        } else if (monthsActive > 12) {
            calculatedPrice = calculatedPrice.subtract(command.basePrice().percentage(10)); // 10% após 1 ano
        }

        if (command.isNonProfit()) {
            calculatedPrice = calculatedPrice.multiply(0.70); // 30% off para ONGs
        }

        if (command.promoCode() != null && !command.promoCode().trim().isEmpty()) {
            if (command.promoCode().equalsIgnoreCase("BLACKFRIDAY") && today.getMonthValue() == 11) {
                calculatedPrice = calculatedPrice.subtract(Money.of(30.0));
            } else if (command.promoCode().equalsIgnoreCase("WELCOME10") && monthsActive <= 1) {
                calculatedPrice = calculatedPrice.multiply(0.90);
            }
        }

        if (command.overdueDays() > 0) {
            Money penalty = calculatedPrice.percentage(2);
            Money dailyInterest = calculatedPrice.multiply(0.001 * command.overdueDays());
            calculatedPrice = calculatedPrice.add(penalty).add(dailyInterest);
        }

        if (calculatedPrice.isNegative()) {
            calculatedPrice = Money.ZERO;
        }

        try {
            Optional<CustomerRepository.Customer> customer = customerRepository.findById(command.customerId());

            if (customer.isPresent()) {
                CustomerRepository.Customer customerData = customer.get();

                if (!"ACTIVE".equalsIgnoreCase(customerData.status())) {
                    logs.add("Cliente inativo. Cobrança cancelada: " + command.customerId());
                    return logs;
                }

                boolean paymentSuccess = paymentGateway.processPayment(
                    command.paymentCardToken(), calculatedPrice
                );
                if (paymentSuccess) {
                    logs.add("Pagamento processado com sucesso: R$ " + calculatedPrice);
                } else {
                    logs.add("Falha no pagamento do cartão para: " + command.customerId());
                }

                customerRepository.saveInvoice(
                    command.customerId(),
                    calculatedPrice,
                    paymentSuccess ? "PAID" : "FAILED",
                    today
                );

                notificationService.sendInvoice(customerData.email(), calculatedPrice);
                // emailSender.send();
                logs.add("E-mail disparado para: " + customerData.email());
            }
        } catch (Exception e) {
            logs.add("Erro fatal no processamento: " + e.getMessage());
        }

        return logs;
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
