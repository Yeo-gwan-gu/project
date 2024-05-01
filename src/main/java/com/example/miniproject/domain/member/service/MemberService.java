package com.example.miniproject.domain.member.service;

import com.example.miniproject.common.service.ImageService;
import com.example.miniproject.domain.hotel.dto.HotelDTO;
import com.example.miniproject.domain.hotel.repository.FavoriteRepository;
import com.example.miniproject.domain.member.constant.MemberRole;
import com.example.miniproject.domain.member.constant.MemberStatus;
import com.example.miniproject.domain.member.dto.MemberDTO;
import com.example.miniproject.domain.member.dto.TokenDTO;
import com.example.miniproject.domain.member.entity.Member;
import com.example.miniproject.domain.member.repository.MemberCacheRepository;
import com.example.miniproject.domain.member.repository.MemberRepository;
import com.example.miniproject.domain.order.constant.OrderStatus;
import com.example.miniproject.domain.order.dto.OrderDTO;
import com.example.miniproject.domain.order.repository.OrderRepository;
import com.example.miniproject.exception.ApiErrorCode;
import com.example.miniproject.exception.ApiException;
import com.example.miniproject.util.JwtTokenUtil;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

@RequiredArgsConstructor
@Transactional
@Service
@Slf4j
public class MemberService {
    private final OrderRepository orderRepository;
    private final FavoriteRepository favoriteRepository;
    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final JwtTokenUtil jwtTokenUtil;
    private final ImageService imageService;
    private final MemberCacheRepository memberCacheRepository;

    @Value("${spring.mail.username}")
    private String mailSenderUsername;

    public MemberDTO.JoinResponse create(MemberDTO.JoinRequest request) throws Exception {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(ApiErrorCode.DUPLICATED_EMAIL.getDescription());
        }

        String uuid = UUID.randomUUID().toString();
        Member newMember = Member.saveAs(
          request.getEmail(),
          passwordEncoder.encode(request.getPassword()),
          request.getName(),
          request.getBirth(),
          uuid);
        sendMail(newMember, uuid);
        memberRepository.save(newMember);
        return MemberDTO.JoinResponse.of(newMember);
    }

    public void updateCertificate(String uuid) {
        memberRepository.findByUuid(uuid)
          .map(member -> {
              member.updateStatus(MemberStatus.CERTIFICATED);
              return member;
          })
          .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND_MEMBER.getDescription()));
    }

    public MemberDTO.LoginResponse login(MemberDTO.LoginRequest request) {
        Member member = getValidMemberOrThrow(request.getEmail());

        validatePasswordWithThrow(request.getPassword(), member.getPassword());

        TokenDTO tokenDTO = jwtTokenUtil.generatedToken(member.getEmail());
        member.updateRefreshToken(tokenDTO.getRefreshToken());

        memberCacheRepository.setMember(member);

        return MemberDTO.LoginResponse.of(member, tokenDTO.getAccessToken());
    }

    public void uploadProfile(String email, MultipartFile file) {
        Member member = getValidMemberOrThrow(email);
        try {
            String imgUrl = imageService.upload(file, UUID.randomUUID().toString());
            member.updateProfileImage(imgUrl);
            log.info("member upload profile image url : {}", imgUrl);
            memberRepository.save(member);
        } catch (IOException e) {
            throw new ApiException(ApiErrorCode.FIREBASE_EXCEPTION.getDescription());
        }
        memberRepository.save(member);
        memberCacheRepository.setMember(member);
    }

    @Transactional(readOnly = true)
    public MemberDTO.DetailResponse getMyPageInfo(String email) {
        Member member = getValidMemberOrThrow(email);
        return MemberDTO.DetailResponse.of(member);
    }

    public void updateMemberInfo(String email, MemberDTO.UpdateMemberRequest request) {
        Member member = getValidMemberOrThrow(email);

        validatePasswordWithThrow(request.getPassword(), member.getPassword());

        member.updateAdditionalInfo(
          request.getZipCode(), request.getNation(), request.getCity(), request.getAddress()
        );
        memberRepository.save(member);
    }

    public void updateMember(Member member) {
        memberRepository.save(member);
        memberCacheRepository.setMember(member);
    }

    public Page<HotelDTO.Response> getMyFavoriteList(String email, Pageable pageable) {
        Member member = getValidMemberOrThrow(email);
        return favoriteRepository.findAllByMemberId(member.getId(), pageable)
          .map(favorite -> {
              HotelDTO.Response response = HotelDTO.Response.of(favorite.getHotel());
              response.updateFavorite(true);
              return response;
          });
    }

    public Page<OrderDTO.OrderDetailResponse> getMyCartList(String email, Pageable pageable) {
        Member member = getValidMemberOrThrow(email);
        return orderRepository.findAllByMemberIdAndStatus(member.getId(), OrderStatus.PAYMENT_PENDING, pageable)
          .map(OrderDTO.OrderDetailResponse::of);
    }

    public void removeCartItem(String email, Long orderId) {
        Member member = getValidMemberOrThrow(email);
        orderRepository.findByIdAndMemberIdAndStatus(orderId, member.getId(), OrderStatus.PAYMENT_PENDING)
          .map(order -> {
              order.updateStatus(OrderStatus.WITHDRAW_ORDER);
              return order;
          })
          .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND_ORDER.getDescription()));
    }

    public Page<OrderDTO.OrderDetailResponse> getMyOrderList(String email, Pageable pageable) {
        Member member = getValidMemberOrThrow(email);
        return orderRepository.findAllByMemberIdAndStatus(member.getId(), OrderStatus.PAYMENT_COMPLETED, pageable)
          .map(OrderDTO.OrderDetailResponse::of);
    }

    public Member getValidMemberOrThrow(String email) {
        return memberCacheRepository.getMember(email)
          .orElseGet(() -> memberRepository.findByEmailAndStatus(email, MemberStatus.CERTIFICATED)
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND_MEMBER.getDescription())));
    }

    public Member getMasterMemberOrThrow(String email) {
        return memberRepository.findByEmailAndRole(email, MemberRole.MASTER)
          .orElseThrow(() -> new ApiException(ApiErrorCode.NO_PERMISSION.getDescription()));
    }

    private void validatePasswordWithThrow(String password, String encodedPassword) {
        if (!passwordEncoder.matches(password, encodedPassword)) {
            throw new ApiException(ApiErrorCode.NOT_MATCH_PASSWORD.getDescription());
        }
    }

    @Async
    protected void sendMail(Member newMember, String uuid) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage);

        mimeMessage.addRecipients(Message.RecipientType.TO, newMember.getEmail());
        messageHelper.setSubject("가입 인증 메일");
        String body = "<div>"
          + "<h1> 안녕하세요.</h1>"
          + "<br>"
          + "<p>아래 링크를 클릭하면 이메일 인증이 완료됩니다.<p>"
          + "<a href='http://52.78.12.252:8080/api/members/verify?uuid=" + uuid + "'>인증 링크</a>"
          + "</div>";
        messageHelper.setText(body, true);
        messageHelper.setFrom(new InternetAddress(mailSenderUsername, "MASTER"));
        mailSender.send(mimeMessage);
    }

    public MemberDTO.SimpleResponse updateCredit(String email, BigDecimal credit) {
        Member member = getValidMemberOrThrow(email);
        member.updateCredit(credit);
        updateMember(member);
        return MemberDTO.SimpleResponse.of(member);
    }

    public MemberDTO.UpdateMemberRole updateRole(String email, MemberDTO.UpdateMemberRole request) {
        getMasterMemberOrThrow(email);
        Member targetMember = getValidMemberOrThrow(request.getEmail());
        targetMember.updateRole(request.getMemberRole());
        updateMember(targetMember);
        return MemberDTO.UpdateMemberRole.of(targetMember);
    }
}
