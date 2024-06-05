package com.skyhorsemanpower.auction.presentation;

import com.skyhorsemanpower.auction.application.AuctionService;
import com.skyhorsemanpower.auction.common.CustomException;
import com.skyhorsemanpower.auction.common.SuccessResponse;
import com.skyhorsemanpower.auction.data.dto.CreatedAuctionHistoryDto;
import com.skyhorsemanpower.auction.data.dto.SearchAllAuctionDto;
import com.skyhorsemanpower.auction.data.dto.SearchAuctionDto;
import com.skyhorsemanpower.auction.data.dto.SearchBiddingPriceDto;
import com.skyhorsemanpower.auction.data.vo.*;
import com.skyhorsemanpower.auction.domain.AuctionHistory;
import com.skyhorsemanpower.auction.repository.cqrs.read.ReadOnlyAuctionRepository;
import com.skyhorsemanpower.auction.status.ResponseStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "인가가 필요없는 경매 서비스", description = "인가가 필요없는 경매 서비스 API")
@RequestMapping("/api/v1/non-authorization/auction")
public class NonAuthorizationAuctionController {
    private final AuctionService auctionService;
    private final ReadOnlyAuctionRepository readOnlyAuctionRepository;

    // 키워드와 카테고리를 통한 경매 리스트 조회
    @GetMapping("/search")
    @Operation(summary = "경매 리스트 조회", description = "경매 리스트 조회")
    public SuccessResponse<SearchAllAuctionResponseVo> searchAllAuction(
            @RequestHeader(required = false) String authorization,
            @RequestHeader(required = false) String uuid,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return new SuccessResponse<>(auctionService.searchAllAuction(SearchAllAuctionDto.builder().keyword(keyword)
                .category(category).page(page).size(size).uuid(uuid).token(authorization).build()));
    }

    // auction_uuid를 통한 경매 상세 조회
    @GetMapping("/{auctionUuid}")
    @Operation(summary = "경매 상세 조회", description = "경매 상세 조회")
    public SuccessResponse<SearchAuctionResponseVo> searchAuction(
            @PathVariable("auctionUuid") String auctionUuid,
            @RequestHeader(required = false) String authorization,
            @RequestHeader(required = false) String uuid) {
        SearchAuctionResponseVo searchAuctionResponseVo = auctionService.searchAuction(SearchAuctionDto.builder()
                .auctionUuid(auctionUuid).token(authorization).uuid(uuid).build());
        return new SuccessResponse<>(searchAuctionResponseVo);
    }

    // 경매 입찰 내역 조회
    @GetMapping(value = "/{auctionUuid}/bidding-history", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "경매 입찰 내역 조회", description = "경매 입찰 내역 조회")
    public Flux<AuctionHistory> searchBiddingPrice(
            @PathVariable("auctionUuid") String auctionUuid) {
        return auctionService.searchBiddingPrice(SearchBiddingPriceDto.builder().auctionUuid(auctionUuid).build())
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 메인 페이지_통계
    @GetMapping("/statistic")
    @Operation(summary = "메인 페이지_통계", description = "메인 페이지_통계")
    public SuccessResponse<MainStatisticResponseVo> mainStatistic() {
        return new SuccessResponse<>(auctionService.mainStatistic());
    }

    // 메인 페이지_핫 경매글
    @GetMapping("/hot-auction")
    @Operation(summary = "메인 페이지_핫경매글", description = "메인 페이지_핫경매글")
    public SuccessResponse<List<MainHotAuctionResponseVo>> mainHotAuction() {
        return new SuccessResponse<>(auctionService.mainHotAuction());
    }

    // 메인 페이지_카테고리 핫 경매글
    @GetMapping("/category-hot-auction")
    @Operation(summary = "메인 페이지_카테고리 핫경매글", description = "메인 페이지_카테고리 핫경매글")
    public SuccessResponse<List<MainCategoryHotAuctionResponseVo>> mainCategoryHotAuction() {
        return new SuccessResponse<>(auctionService.mainCategoryHotAuction());
    }

    // 메인 페이지_높은 입찰가 경매글
    @GetMapping("/high-bidding-statistics")
    @Operation(summary = "메인 페이지_높은 입찰가 경매글", description = "메인 페이지_높은 입찰가 경매글")
    public SuccessResponse<List<MainHighBiddingPriceAuctionResponseVo>> mainHighBiddingPriceAuction() {
        return new SuccessResponse<>(auctionService.mainHighBiddingPriceAuction());
    }

    // 구독 서비스에 auctionUuid 요청 시, sellerUuid 반환
    @GetMapping("/seller-uuid")
    @Operation(summary = "경매글 작성자 uuid 조회(백엔드 통신)", description = "구독 서비스에 auctionUuid 요청 시, sellerUuid 반환")
    public SuccessResponse<String> getSellerUuid(
            @RequestHeader String auctionUuid) {
        return new SuccessResponse<>(readOnlyAuctionRepository.findByAuctionUuid(auctionUuid).orElseThrow(
                () -> new CustomException(ResponseStatus.NO_DATA)
        ).getSellerUuid());
    }

    // 경매글 이력 조회
    @GetMapping("/history")
    @Operation(summary = "특정 인물이 작성한 경매글 이력 조회", description = "특정 인물이 작성한 경매글 이력 조회")
    public SuccessResponse<List<CreatedAuctionHistoryResponseVo>> createdAuctionHistory(
            @RequestParam("handle") String handle) {
        List<CreatedAuctionHistoryResponseVo> createdAuctionHistoryResponseVos = auctionService.
                createdAuctionHistory(CreatedAuctionHistoryDto.builder().handle(handle).build());
        return new SuccessResponse<>(createdAuctionHistoryResponseVos);
    }
}
