package com.paymybuddy.paymybuddy.service;

import com.paymybuddy.paymybuddy.constants.Fee;
import com.paymybuddy.paymybuddy.exceptions.*;
import com.paymybuddy.paymybuddy.model.Transaction;
import com.paymybuddy.paymybuddy.model.User;
import com.paymybuddy.paymybuddy.model.viewmodel.TransactionViewModel;
import com.paymybuddy.paymybuddy.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @InjectMocks
    private TransactionService transactionService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private UserService userService;

    @Mock
    private PaginationService paginationService;

    @Mock
    private Clock clock;

    private static final LocalDateTime LOCAL_DATE_NOW =
            LocalDateTime.of(2022, 7, 18, 10, 0, 0);

    private User issuer;
    private User payee;
    private Transaction transaction;

    @BeforeEach
    void setup() {

        issuer = new User();
        issuer.setId(1);
        issuer.setFirstName("Chandler");
        issuer.setBalance(new BigDecimal("500"));

        payee = new User();
        payee.setId(2);
        payee.setFirstName("Joey");
        payee.setBalance(new BigDecimal("0"));

        transaction = new Transaction(
                1,
                issuer,
                payee,
                LOCAL_DATE_NOW,
                new BigDecimal("20"),
                "transaction test"
        );

        Clock fixedClock = Clock.fixed(
                LOCAL_DATE_NOW.atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()
        );

        when(clock.instant()).thenReturn(fixedClock.instant());
        when(clock.getZone()).thenReturn(fixedClock.getZone());
    }

    @Test
    void createTransaction_whenAmountIsNegative() {
        assertThrows(InvalidAmountException.class,
                () -> transactionService.createTransaction(issuer, payee, "desc", -50));
    }

    @Test
    void createTransaction_whenIssuerIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.createTransaction(null, payee, "desc", 30));
    }

    @Test
    void createTransaction_whenPayeeIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.createTransaction(issuer, null, "desc", 30));
    }

    @Test
    void createTransaction_whenAmountIsZero() {
        assertThrows(InvalidAmountException.class,
                () -> transactionService.createTransaction(issuer, payee, "desc", 0));
    }

    @Test
    void createTransaction_whenIssuerHasInsufficientBalance() {
        assertThrows(InsufficientBalanceException.class,
                () -> transactionService.createTransaction(issuer, payee, "desc", 1000));
    }

    @Test
    void createTransaction_whenPayeeNotInConnections() {
        when(connectionService.getUserConnections(any()))
                .thenReturn(Collections.emptyList());

        assertThrows(InvalidPayeeException.class,
                () -> transactionService.createTransaction(issuer, payee, "desc", 50));
    }

    @Test
    void createTransaction_shouldUpdateIssuerBalance() {

        when(connectionService.getUserConnections(issuer))
                .thenReturn(List.of(UserService.userToViewModel(payee)));

        BigDecimal before = issuer.getBalance();
        double amount = 100;
        double fee = amount * Fee.TRANSACTION_FEE;

        BigDecimal total = new BigDecimal(amount + fee)
                .setScale(Fee.SCALE, RoundingMode.HALF_UP);

        transactionService.createTransaction(issuer, payee, "desc", amount);

        assertThat(issuer.getBalance())
                .isEqualTo(before.subtract(total));
    }

    @Test
    void createTransaction_shouldUpdatePayeeBalance() {

        when(connectionService.getUserConnections(issuer))
                .thenReturn(List.of(UserService.userToViewModel(payee)));

        BigDecimal before = payee.getBalance();

        transactionService.createTransaction(issuer, payee, "desc", 100);

        assertThat(payee.getBalance())
                .isEqualTo(before.add(new BigDecimal("100")
                        .setScale(Fee.SCALE, RoundingMode.HALF_UP)));
    }

    @Test
    void getUserTransactions_shouldReturnTransactions() {

        when(transactionRepository.findByIssuerOrPayee(issuer, issuer))
                .thenReturn(List.of(transaction));

        when(userService.getUserById(issuer.getId()))
                .thenReturn(Optional.of(issuer));

        List<TransactionViewModel> result =
                transactionService.getUserTransactions(issuer.getId());

        assertTrue(result.contains(
                TransactionService.transactionToViewModel(transaction)
        ));
    }

    @Test
    void getTransactionById_whenNull_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.getTransactionById(null));
    }

    @Test
    void getUserTransactions_whenNull_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> transactionService.getUserTransactions(null));
    }
}
