package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;

/**
 * Adapts core-proxy's {@code routing} {@link JsonCodec} SPI (parse/stringify) to core-ir's own,
 * structurally identical {@link io.github.intisy.ai.ir.spi.JsonCodec}. Every call site in
 * this module already carries a routing {@code JsonCodec} (GsonJsonCodec on the JVM,
 * SimpleJsonCodec from the TeaVM export); this lets {@link AnthropicRequestTranslator} hand that
 * same instance to core-ir's {@code AnthropicTranslator} instead of duplicating a codec. Mirrors
 * stub-auth's {@code StubProvider.RoutingJsonCodecAdapter}.
 */
public final class IrJsonCodecAdapter implements io.github.intisy.ai.ir.spi.JsonCodec {
    private final JsonCodec delegate;

    public IrJsonCodecAdapter(JsonCodec delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object parse(String json) {
        return delegate.parse(json);
    }

    @Override
    public String stringify(Object value) {
        return delegate.stringify(value);
    }
}
