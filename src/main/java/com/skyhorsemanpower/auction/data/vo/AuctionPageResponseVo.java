package com.skyhorsemanpower.auction.data.vo;

import com.skyhorsemanpower.auction.common.redis.RedisVariableEnum;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Getter
@NoArgsConstructor
public class AuctionPageResponseVo {
    private int round;
    private LocalDateTime currentRoundStartTime;
    private LocalDateTime currentRoundEndTime;
    private BigDecimal currentPrice;
    private int numberOfEventParticipants;

    @Builder
    public AuctionPageResponseVo(int round, LocalDateTime currentRoundStartTime, LocalDateTime currentRoundEndTime,
                                 BigDecimal currentPrice, int numberOfEventParticipants) {
        this.round = round;
        this.currentRoundStartTime = currentRoundStartTime;
        this.currentRoundEndTime = currentRoundEndTime;
        this.currentPrice = currentPrice;
        this.numberOfEventParticipants = numberOfEventParticipants;
    }

    public static AuctionPageResponseVo converter(Map<Object, Object> resultMap) {
        return AuctionPageResponseVo.builder()
                .round(Integer.parseInt((String) resultMap.get(RedisVariableEnum.ROUND.getVariable())))
                .currentRoundStartTime(LocalDateTime.parse((String) resultMap.get(
                        RedisVariableEnum.CURRENT_ROUND_START_TIME.getVariable()), DateTimeFormatter.ISO_DATE_TIME))
                .currentRoundEndTime(LocalDateTime.parse((String) resultMap.get(
                        RedisVariableEnum.CURRENT_ROUND_END_TIME.getVariable()), DateTimeFormatter.ISO_DATE_TIME))
                .currentPrice(new BigDecimal((String) resultMap.get(RedisVariableEnum.CURRENT_PRICE.getVariable())))
                .numberOfEventParticipants(Integer.parseInt((String) resultMap.get(
                        RedisVariableEnum.NUMBER_OF_EVENT_PARTICIPANTS.getVariable())))
                .build();
    }
}