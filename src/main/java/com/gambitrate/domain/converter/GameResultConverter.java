package com.gambitrate.domain.converter;

import com.gambitrate.domain.GameResult;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class GameResultConverter implements AttributeConverter<GameResult, String> {

    @Override
    public String convertToDatabaseColumn(GameResult attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public GameResult convertToEntityAttribute(String dbData) {
        return dbData == null ? null : GameResult.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
