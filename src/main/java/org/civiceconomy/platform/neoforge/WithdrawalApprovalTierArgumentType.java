package org.civiceconomy.platform.neoforge;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.Collection;
import java.util.List;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;

final class WithdrawalApprovalTierArgumentType
        implements ArgumentType<List<WithdrawalApprovalTier>> {
    private static final DynamicCommandExceptionType INVALID_TIERS =
            new DynamicCommandExceptionType(message -> new LiteralMessage(
                    "Invalid Withdrawal Approval tiers: " + message));

    private WithdrawalApprovalTierArgumentType() {}

    static WithdrawalApprovalTierArgumentType tiers() {
        return new WithdrawalApprovalTierArgumentType();
    }

    @SuppressWarnings("unchecked")
    static List<WithdrawalApprovalTier> getTiers(
            CommandContext<?> context, String name) {
        return context.getArgument(name, List.class);
    }

    @Override
    public List<WithdrawalApprovalTier> parse(StringReader reader)
            throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        String specification = reader.getString().substring(start, reader.getCursor());
        try {
            return WithdrawalApprovalTierParser.parse(specification);
        } catch (IllegalArgumentException failure) {
            reader.setCursor(start);
            throw INVALID_TIERS.createWithContext(reader, failure.getMessage());
        }
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("0:1,500:2,2000:3");
    }
}
