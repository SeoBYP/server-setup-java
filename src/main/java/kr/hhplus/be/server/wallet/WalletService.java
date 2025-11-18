package kr.hhplus.be.server.wallet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class WalletService {

    @Autowired
    private WalletRepository walletRepository;


    @Transactional
    public void charge(Long userId, BigDecimal amount)
    {
        var wallet = walletRepository.findForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("WALLET_NOT_FOUND"));
        wallet.charge(amount);
    }

    @Transactional
    public void debit(Long userId, BigDecimal amount) {

        var wallet = walletRepository.findForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("WALLET_NOT_FOUND"));
        wallet.debit(amount);
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long userId) {
        return walletRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("WALLET_NOT_FOUND"))
                .getBalance();
    }
}
