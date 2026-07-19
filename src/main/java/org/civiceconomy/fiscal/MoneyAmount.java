package org.civiceconomy.fiscal;

public record MoneyAmount(long minorUnits) implements Comparable<MoneyAmount> {
    public static final MoneyAmount ZERO = new MoneyAmount(0);

    public MoneyAmount {
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative: " + minorUnits);
        }
    }

    public static MoneyAmount ofMinorUnits(long minorUnits) {
        return new MoneyAmount(minorUnits);
    }

    public MoneyAmount plus(MoneyAmount other) {
        return new MoneyAmount(Math.addExact(minorUnits, other.minorUnits));
    }

    public MoneyAmount minus(MoneyAmount other) {
        return new MoneyAmount(Math.subtractExact(minorUnits, other.minorUnits));
    }

    @Override
    public int compareTo(MoneyAmount other) {
        return Long.compare(minorUnits, other.minorUnits);
    }
}
