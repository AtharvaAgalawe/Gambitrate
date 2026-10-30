package com.gambitrate.domain.converter;

import com.gambitrate.domain.PlayerColor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class PlayerColorConverter implements AttributeConverter<PlayerColor, String> {

    @Override
    public String convertToDatabaseColumn(PlayerColor attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public PlayerColor convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PlayerColor.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
