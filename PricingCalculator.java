package com.saas.billing;

import java.time.LocalDate;

public class PricingCalculator {

    public Money calculate(BillingCommand command, LocalDate today) {
        LocalDate signupDate = LocalDate.parse(command.signupDateStr());
        int monthsActive = calculateMonthsActive(signupDate, today);
        Money calculatedPrice = command.basePrice();

        if (monthsActive > 24) {
            calculatedPrice = calculatedPrice.subtract(command.basePrice().percentage(15)); // 15% após 2 anos
        } else if (monthsActive > 12) {
            calculatedPrice = calculatedPrice.subtract(command.basePrice().percentage(10)); // 10% após 1 ano
        }

        if (command.isNonProfit()) {
            calculatedPrice = calculatedPrice.multiply(0.70); // 30% off para ONGs
        }

        calculatedPrice = applyPromoCode(calculatedPrice, command, today, monthsActive);
        calculatedPrice = applyLatePaymentCharges(calculatedPrice, command.overdueDays());

        return calculatedPrice.isNegative() ? Money.ZERO : calculatedPrice;
    }

    private int calculateMonthsActive(LocalDate signupDate, LocalDate today) {
        return (today.getYear() - signupDate.getYear()) * 12
            + (today.getMonthValue() - signupDate.getMonthValue());
    }

    private Money applyPromoCode(Money price, BillingCommand command,
                                 LocalDate today, int monthsActive) {
        if (command.promoCode() == null || command.promoCode().trim().isEmpty()) {
            return price;
        }

        if (command.promoCode().equalsIgnoreCase("BLACKFRIDAY")
            && today.getMonthValue() == 11) {
            return price.subtract(Money.of(30.0));
        }

        if (command.promoCode().equalsIgnoreCase("WELCOME10") && monthsActive <= 1) {
            return price.multiply(0.90);
        }

        return price;
    }

    private Money applyLatePaymentCharges(Money price, int overdueDays) {
        if (overdueDays <= 0) {
            return price;
        }

        Money penalty = price.percentage(2);
        Money dailyInterest = price.multiply(0.001 * overdueDays);
        return price.add(penalty).add(dailyInterest);
    }
}
