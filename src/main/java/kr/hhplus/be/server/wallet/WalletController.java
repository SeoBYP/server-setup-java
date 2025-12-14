package kr.hhplus.be.server.wallet;

import kr.hhplus.be.server.wallet.DTO.ChargeRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {
    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletFacade walletFacade;

    @PostMapping("/{userId}/charge")
    public ResponseEntity<Void> charge(@PathVariable Long userId, @RequestBody ChargeRequest request)
    {
        walletFacade.charge(userId,request.getAmount());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{userId}/debit")
    public ResponseEntity<Void> debit(@PathVariable Long userId, @RequestBody ChargeRequest request)
    {
        walletFacade.debit(userId,request.getAmount());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{userId}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long userId)
    {
        BigDecimal balance = walletService.getBalance(userId);
        return ResponseEntity.ok(balance);
    }
}
