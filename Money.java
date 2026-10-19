package com.saas.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value Object imutável que representa valores monetários.
 * Encapsula regras financeiras de precisão decimal, arredondamento (HALF_UP com 2 casas decimais)
 * e operações aritméticas para eliminar o code smell de Primitive Obsession.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;
    public static final int DEFAULT_SCALE = 2;

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "O valor monetário não pode ser nulo.");
        amount = amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    public static Money of(double value) {
        return new Money(BigDecimal.valueOf(value));
    }

    public static Money of(BigDecimal value) {
        return new Money(value);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "O valor a ser adicionado não pode ser nulo.");
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        Objects.requireNonNull(other, "O valor a ser subtraído não pode ser nulo.");
        return new Money(this.amount.subtract(other.amount));
    }

    public Money multiply(double factor) {
        return multiply(BigDecimal.valueOf(factor));
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "O fator de multiplicação não pode ser nulo.");
        return new Money(this.amount.multiply(factor).setScale(DEFAULT_SCALE, DEFAULT_ROUNDING));
    }

    public Money percentage(double percentage) {
        BigDecimal factor = BigDecimal.valueOf(percentage).divide(BigDecimal.valueOf(100), 4, DEFAULT_ROUNDING);
        return multiply(factor);
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isLessThan(Money other) {
        return this.compareTo(other) < 0;
    }

    public boolean isGreaterThan(Money other) {
        return this.compareTo(other) > 0;
    }

    public double doubleValue() {
        return this.amount.doubleValue();
    }

    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "Valor monetário de comparação não pode ser nulo.");
        return this.amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toString();
    }
}
