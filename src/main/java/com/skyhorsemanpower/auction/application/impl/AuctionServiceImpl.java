package com.skyhorsemanpower.auction.application.impl;

import com.skyhorsemanpower.auction.application.AuctionService;
import com.skyhorsemanpower.auction.common.exception.CustomException;
import com.skyhorsemanpower.auction.data.vo.AuctionResultResponseVo;
import com.skyhorsemanpower.auction.domain.AuctionCloseState;
import com.skyhorsemanpower.auction.domain.AuctionResult;
import com.skyhorsemanpower.auction.domain.RoundInfo;
import com.skyhorsemanpower.auction.kafka.KafkaProducerCluster;
import com.skyhorsemanpower.auction.kafka.Topics;
import com.skyhorsemanpower.auction.kafka.data.MessageEnum;
import com.skyhorsemanpower.auction.kafka.data.dto.AlarmDto;
import com.skyhorsemanpower.auction.kafka.data.dto.AuctionCloseDto;
import com.skyhorsemanpower.auction.quartz.data.MemberUuidsAndPrice;
import com.skyhorsemanpower.auction.repository.*;
import com.skyhorsemanpower.auction.common.exception.ResponseStatus;
import com.skyhorsemanpower.auction.data.dto.*;
import com.skyhorsemanpower.auction.domain.AuctionHistory;
import com.skyhorsemanpower.auction.status.AuctionStateEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionServiceImpl implements AuctionService {

    private final AuctionHistoryRepository auctionHistoryRepository;
    private final RoundInfoRepository roundInfoRepository;
    private final AuctionCloseStateRepository auctionCloseStateRepository;
    private final KafkaProducerCluster producer;
    private final AuctionResultRepository auctionResultRepository;

    @Override
    @Transactional
    public void offerBiddingPrice(OfferBiddingPriceDto offerBiddingPriceDto) {

        // 현재 경매의 라운드 정보 추출
        RoundInfo roundInfo = roundInfoRepository.
                findFirstByAuctionUuidOrderByCreatedAtDesc(offerBiddingPriceDto.getAuctionUuid()).orElseThrow(
                        () -> new CustomException(ResponseStatus.NO_DATA));

        // 입찰 가능 확인
        // 입찰이 안되면 아래 메서드 내에서 예외를 던진다.
        // isUpdateRoundInfo boolean 데이터는 round_info 도큐먼트를 갱신 트리거
        isBiddingPossible(offerBiddingPriceDto, roundInfo);

        // 입찰 정보 저장
        AuctionHistory auctionHistory = AuctionHistory.converter(offerBiddingPriceDto);
        log.info("Saved Auction History Information >>> {}", auctionHistory.toString());

        try {
            auctionHistoryRepository.save(auctionHistory);
        } catch (Exception e) {
            throw new CustomException(ResponseStatus.MONGODB_ERROR);
        }

        // 입찰 후, round_info 도큐먼트 갱신
        updateRoundInfo(roundInfo);
    }

    @Override
    public void auctionClose(String auctionUuid) {
        // auction_close_state 도큐먼트에 acutionUuid 데이터가 있으면(마감됐으면) 바로 return
        if (auctionCloseStateRepository.findByAuctionUuid(auctionUuid).isPresent()) {
            log.info("Auction already close");
            return;
        }

        // auction_history 도큐먼트를 조회하여 경매 상태를 변경
        if (auctionHistoryRepository.findFirstByAuctionUuidOrderByBiddingTimeDesc(auctionUuid).isEmpty()) {
            log.info("auction_history is not exist! No one bid the auction!");

            // 아무도 참여하지 않은 경우에는 auctionUuid와 auctionState(AUCTION_NO_PARTICIPANTS) 전송
            AuctionCloseDto noParticipantsAuctionCloseDto = AuctionCloseDto.builder()
                    .auctionUuid(auctionUuid)
                    .auctionState(AuctionStateEnum.AUCTION_NO_PARTICIPANTS)
                    .build();
            log.info("No one bid the auction message >>> {}", noParticipantsAuctionCloseDto.toString());
            producer.sendMessage(Topics.Constant.AUCTION_CLOSE, noParticipantsAuctionCloseDto);

            // 경매 마감 여부 저장
            auctionCloseStateRepository.save(AuctionCloseState.builder()
                    .auctionUuid(auctionUuid)
                    .auctionCloseState(true)
                    .build());
            return;
        }

        log.info("auction_history is exist!");

        // 경매 마감 로직
        // 마지막 라운드 수, 낙찰 가능 인원 수 조회
        RoundInfo lastRoundInfo = roundInfoRepository.findFirstByAuctionUuidOrderByCreatedAtDesc(auctionUuid)
                .orElseThrow(() -> new CustomException(ResponseStatus.NO_DATA)
                );
        log.info("Last Round Info >>> {}", lastRoundInfo.toString());

        int round = lastRoundInfo.getRound();
        long numberOfParticipants = lastRoundInfo.getNumberOfParticipants();

        // 마감 로직
        MemberUuidsAndPrice memberUuidsAndPrice = getMemberUuidsAndPrice(
                round, auctionUuid, numberOfParticipants);

        // 낙찰가와 낙찰자 획득
        Set<String> memberUuids = memberUuidsAndPrice.getMemberUuids();
        BigDecimal price = memberUuidsAndPrice.getPrice();

        // 카프카로 경매 서비스 메시지 전달
        AuctionCloseDto auctionCloseDto = AuctionCloseDto.builder()
                .auctionUuid(auctionUuid)
                .memberUuids(memberUuids.stream().toList())
                .price(price)
                .auctionState(AuctionStateEnum.AUCTION_NORMAL_CLOSING)
                .build();
        log.info("Kafka Message To Payment Service >>> {}", auctionCloseDto.toString());

        // 경매글 마감 처리 메시지와 결제 서비스 메시지 동일 토픽으로 진행
        producer.sendMessage(Topics.Constant.AUCTION_CLOSE, auctionCloseDto);

        // 알람 서비스로 메시지 전달
        AlarmDto alarmDto = AlarmDto.builder().receiverUuids(memberUuids.stream().toList())
                .message(MessageEnum.Constant.AUCTION_CLOSE_MESSAGE)
                .eventType("경매")
                .build();
        log.info("Auction Close Message To Alarm Service >>> {}", alarmDto.toString());

        producer.sendMessage(Topics.Constant.ALARM, alarmDto);

        // 경매 마감 여부 저장
        auctionCloseStateRepository.save(AuctionCloseState.builder()
                .auctionUuid(auctionUuid)
                .auctionCloseState(true)
                .build());

        // 경매 결과 저장
        auctionResultRepository.save(AuctionResult.builder()
                .auctionUuid(auctionUuid)
                .memberUuids(memberUuids.stream().toList())
                .price(price)
                .build());
        log.info("Auction Result Save!");
    }

    private MemberUuidsAndPrice getMemberUuidsAndPrice(int round, String auctionUuid, long numberOfParticipants) {
        Set<String> memberUuids = new HashSet<>();
        BigDecimal price;

        // 마지막 라운드 입찰 이력
        List<AuctionHistory> lastRoundAuctionHistory = auctionHistoryRepository.
                findByAuctionUuidAndRoundOrderByBiddingTime(auctionUuid, round);
        log.info("Last Round Auction History >>> {}", lastRoundAuctionHistory.toString());

        // 1라운드에서 경매가 마감된 경우
        if (round == 1) {
            log.info("One Round Close");
            // 마지막 라운드 입찰자를 낙찰자로 고정
            for (AuctionHistory auctionHistory : lastRoundAuctionHistory) {
                memberUuids.add(auctionHistory.getBiddingUuid());
            }

            log.info("memberUuids >>> {}", memberUuids.toString());

            // 낙찰가는 마지막 라운드에서 biddingPrice로 결정
            price = lastRoundAuctionHistory.get(0).getBiddingPrice();
            log.info("price >>> {}", price);
        }

        // 1라운드 제외한 라운드에서 경매가 마감된 경우
        else {
            log.info("{} Round Close", round);

            // 마지막 - 1 라운드 입찰 이력
            List<AuctionHistory> lastMinusOneRoundAuctionHistory = auctionHistoryRepository.
                    findByAuctionUuidAndRoundOrderByBiddingTime(auctionUuid, round - 1);
            log.info("Before Last Round Auction History >>> {}", lastMinusOneRoundAuctionHistory.toString());

            // 마지막 라운드 입찰자를 낙찰자로 고정
            for (AuctionHistory auctionHistory : lastRoundAuctionHistory) {
                memberUuids.add(auctionHistory.getBiddingUuid());
            }

            // 마지막 직전 라운드 입찰자 중 낙찰자 추가
            for (AuctionHistory auctionHistory : lastMinusOneRoundAuctionHistory) {
                // 동일 입찰자 제외하고 추가
                memberUuids.add(auctionHistory.getBiddingUuid());

                // 낙찰 가능 인원 수 만큼 리스트 추가
                if (memberUuids.size() == numberOfParticipants) break;
            }

            log.info("memberUuids >>> {}", memberUuids.toString());

            // 낙찰가는 마지막 이전 라운드에서 biddingPrice로 결정
            price = lastMinusOneRoundAuctionHistory.get(0).getBiddingPrice();
            log.info("price >>> {}", price);
        }

        return MemberUuidsAndPrice.builder().memberUuids(memberUuids).price(price).build();
    }

    @Override
    public void auctionStateChangeTrue(String auctionUuid) {
        RoundInfo roundInfo = roundInfoRepository.findFirstByAuctionUuidOrderByCreatedAtDesc(auctionUuid).orElseThrow(
                () -> new CustomException(ResponseStatus.NO_DATA)
        );

        try {
            RoundInfo standbyAuction = RoundInfo.setIsActiveTrue(roundInfo);
            log.info("Auction Change isActive >>> {}", standbyAuction.toString());
            roundInfoRepository.save(standbyAuction);
        } catch (Exception e) {
            throw new CustomException(ResponseStatus.MONGODB_ERROR);
        }
    }

    @Override
    public AuctionResultResponseVo auctionResult(String uuid, String auctionUuid) {
        Optional<AuctionResult> auctionResult = auctionResultRepository.
                findByAuctionUuidAndMemberUuidsContains(auctionUuid, uuid);

        // 낙찰자에 포함되지 않는 경우
        if (auctionResult.isEmpty()) {
            log.info("Auction Result is not exist. not bidder");
            return AuctionResultResponseVo.notBidder();
        }

        // 낙찰자에 포함된 경우
        log.info("Auction Result >>> {}", auctionResult.toString());
        return AuctionResultResponseVo.builder()
                .isBidder(true)
                .price(auctionResult.get().getPrice())
                .build();
    }

    private void updateRoundInfo(RoundInfo roundInfo) {
        RoundInfo updatedRoundInfo;

        // 다음 라운드로 round_info 도큐먼트 갱신
        // isActive 대기 상태로 변경
        if (roundInfo.getLeftNumberOfParticipants().equals(1L)) {
            updatedRoundInfo = RoundInfo.nextRoundUpdate(roundInfo);
        }

        // 동일 라운드에서 round_info 도큐먼트 갱신
        else {
            updatedRoundInfo = RoundInfo.currentRoundUpdate(roundInfo);
        }

        log.info("Updated round_info Document >>> {}", updatedRoundInfo.toString());

        try {
            roundInfoRepository.save(updatedRoundInfo);
        } catch (Exception e) {
            throw new CustomException(ResponseStatus.MONGODB_ERROR);
        }
    }

    private void isBiddingPossible(OfferBiddingPriceDto offerBiddingPriceDto, RoundInfo roundInfo) {
        // 조건1. 입찰 시간 확인
        checkBiddingTime(roundInfo.getRoundStartTime(), roundInfo.getRoundEndTime());
        log.info("입찰 시간 통과");

        // 조건2. 해당 라운드에 참여 여부
        checkBiddingRound(offerBiddingPriceDto.getAuctionUuid(), offerBiddingPriceDto.getBiddingUuid(),
                offerBiddingPriceDto.getRound());
        log.info("현재 라운드에 참여한 적 없음");

        // 조건3. 남은 인원이 1 이상
        checkLeftNumberOfParticipant(roundInfo.getLeftNumberOfParticipants());
        log.info("남은 인원 통과");

        // 조건4. round 입찰가와 입력한 입찰가 확인
        checkRoundAndBiddingPrice(offerBiddingPriceDto, roundInfo);
        log.info("라운드 및 입찰가 통과");
    }

    private void checkBiddingRound(String auctionUuid, String biddingUuid, int round) {
        if (auctionHistoryRepository.findByAuctionUuidAndBiddingUuidAndRound(
                auctionUuid, biddingUuid, round).isPresent()) {
            throw new CustomException(ResponseStatus.ALREADY_BID_IN_ROUND);
        }
    }

    private void checkLeftNumberOfParticipant(Long leftNumberOfParticipants) {
        log.info("leftNumberOfParticipants >>> {}", leftNumberOfParticipants);
        if (leftNumberOfParticipants < 1L) throw new CustomException(ResponseStatus.FULL_PARTICIPANTS);
    }

    private void checkRoundAndBiddingPrice(OfferBiddingPriceDto offerBiddingPriceDto, RoundInfo roundInfo) {
        log.info("input round >>> {}, document round >>> {}, input price >>> {}, document price >>> {}",
                offerBiddingPriceDto.getRound(), roundInfo.getRound(),
                offerBiddingPriceDto.getBiddingPrice(), roundInfo.getPrice());

        log.info("inputRound == documentRound >>> {}", offerBiddingPriceDto.getRound() == roundInfo.getRound());
        log.info("inputPrice.compareTo(documentPrice) == 0 >>> {}",
                offerBiddingPriceDto.getBiddingPrice().compareTo(roundInfo.getPrice()) == 0);

        if (!(offerBiddingPriceDto.getBiddingPrice().compareTo(roundInfo.getPrice()) == 0) ||
                !(offerBiddingPriceDto.getRound() == roundInfo.getRound())) {
            throw new CustomException(ResponseStatus.NOT_EQUAL_ROUND_INFORMATION);
        }
    }

    private void checkBiddingTime(LocalDateTime roundStartTime, LocalDateTime roundEndTime) {
        log.info("roundStartTime >>> {}, now >>> {}, roundEndTime >>> {}",
                roundStartTime, LocalDateTime.now(), roundEndTime);
        log.info("roundStartTime.isBefore(LocalDateTime.now()) >>> {}, roundEndTime.isAfter(LocalDateTime.now()) >>> {}"
                , roundStartTime.isBefore(LocalDateTime.now()), roundEndTime.isAfter(LocalDateTime.now()));
        // roundStartTime <= 입찰 시간 <= roundEndTime
        if (!(roundStartTime.isBefore(LocalDateTime.now()) && roundEndTime.isAfter(LocalDateTime.now()))) {
            throw new CustomException(ResponseStatus.NOT_BIDDING_TIME);
        }
    }

}
