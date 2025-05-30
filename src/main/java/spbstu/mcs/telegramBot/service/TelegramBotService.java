package spbstu.mcs.telegramBot.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import spbstu.mcs.telegramBot.DB.services.UserService;
import spbstu.mcs.telegramBot.model.User;
import org.springframework.beans.factory.annotation.Value;
import spbstu.mcs.telegramBot.util.ChatIdMasker;


import reactor.core.publisher.Mono;

/**
 * Сервис для работы с Telegram ботом.
 * Обрабатывает входящие сообщения и команды, отправляет ответы пользователям.
 */
@Service
@Slf4j
public class TelegramBotService extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(TelegramBotService.class);
    private final String botUsername;
    private final KafkaProducerService kafkaProducer;
    private final BotCommand botCommand;
    private final UserService userService;

    @Autowired
    public TelegramBotService(
            @Value("${telegram.bot.token}") String botToken, 
            @Value("${telegram.bot.username}") String botUsername, 
            KafkaProducerService kafkaProducer,
            @Lazy BotCommand botCommand,
            UserService userService) {
        super(botToken);
        logger.info("Initializing TelegramBotService with username: {}", botUsername);
        try {
            this.botUsername = botUsername;
            this.kafkaProducer = kafkaProducer;
            this.botCommand = botCommand;
            this.userService = userService;
            logger.info("TelegramBotService initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize TelegramBotService: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    /**
     * Обрабатывает входящее сообщение.
     * Определяет тип сообщения и вызывает соответствующий обработчик.
     *
     * @param update Объект обновления от Telegram
     */
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = String.valueOf(update.getMessage().getChatId());
            String maskedChatId = ChatIdMasker.maskChatId(chatId);
            String text = update.getMessage().getText();
            
            log.info("Received message from user: {}, text: {}", maskedChatId, text);
            
            // Выделяем команду, если это команда
            boolean isCommand = text.startsWith("/");
            String command = isCommand ? text.split("\\s+", 2)[0].toLowerCase() : "";
            boolean isStartCommand = "/start".equals(command);
            
            // Специальная обработка для команды /start - гарантируем сохранение пользователя до отправки в Kafka
            if (isStartCommand) {
                // Сначала проверяем существование пользователя
                userService.getUserByChatId(chatId)
                    .flatMap(existingUser -> {
                        // Пользователь существует, просто обновим его статус если нужно
                        boolean needsUpdate = !existingUser.isHasStarted();
                        if (needsUpdate) {
                            existingUser.setHasStarted(true);
                            log.info("Updating existing user with hasStarted=true for chatId: {} before sending to Kafka", maskedChatId);
                            return userService.save(existingUser);
                        } else {
                            log.info("User already exists and has started, no need to update for chatId: {}", maskedChatId);
                            return Mono.just(existingUser);
                        }
                    })
                    .switchIfEmpty(Mono.fromCallable(() -> {
                        log.info("User does not exist, creating new user with hasStarted=true for chatId: {}", maskedChatId);
                        User newUser = new User(chatId);
                        newUser.setHasStarted(true);
                        return newUser;
                    }).flatMap(userService::save))
                    .doOnSuccess(user -> {
                        log.info("User processed with hasStarted=true for chatId: {}, now sending to Kafka", maskedChatId);
                        // Отправляем сообщение в Kafka ТОЛЬКО после успешного сохранения пользователя
                        try {
                            kafkaProducer.sendIncomingMessageAsync(chatId, text);
                            log.info("Start command from user {} sent to Kafka after user was saved", maskedChatId);
                        } catch (Exception e) {
                            log.error("Error sending start command to Kafka for user {}: {}", maskedChatId, e.getMessage());
                            // Локальная обработка в случае ошибки Kafka
                            String[] cmdArgs = new String[0];
                            log.warn("Processing /start locally due to Kafka error for user: {}", maskedChatId);
                            botCommand.processCommand(command, cmdArgs, chatId).subscribe();
                        }
                    })
                    .doOnError(error -> log.error("Error processing user for chatId {}: {}", maskedChatId, error.getMessage()))
                    .subscribe();
                return; // Завершаем обработку для /start, т.к. уже отправили в Kafka
            }
            
            // Для всех остальных команд - стандартный поток обработки
            userService.getUserByChatId(chatId)
                .defaultIfEmpty(new User(chatId)) // Если пользователя нет, создаем нового
                .flatMap(user -> {
                    // Для других команд проверяем активность
                    if (isCommand && !user.isHasStarted()) {
                        log.info("User {} not activated, rejecting command {}", maskedChatId, command);
                        // Отправляем сообщение об ошибке напрямую, не через Kafka
                        SendMessage message = new SendMessage(chatId, "❌ Пожалуйста, начните работу с ботом командой /start");
                        try {
                            execute(message);
                            log.info("Sent direct error message to user {}", maskedChatId);
                        } catch (TelegramApiException e) {
                            log.error("Error sending direct message to user {}: {}", maskedChatId, e.getMessage());
                            // Пробуем через Kafka как резервный вариант
                            kafkaProducer.sendOutgoingMessageAsync(chatId, "❌ Пожалуйста, начните работу с ботом командой /start");
                        }
                        return Mono.just(false); // Запрещаем дальнейшую обработку
                    }
                    
                    return Mono.just(true); // Разрешаем обработку для активированных пользователей
                })
                .subscribe(
                    shouldProcess -> {
                        if (shouldProcess) {
                            try {
                                // Отправляем в Kafka только если прошли проверку
                                kafkaProducer.sendIncomingMessageAsync(chatId, text);
                                log.info("Message from user {} sent to Kafka", maskedChatId);
                            } catch (Exception e) {
                                log.error("Error sending message to Kafka for user {}: {}", maskedChatId, e.getMessage());
                                // Локальная обработка в случае ошибки Kafka
                                if (isCommand) {
                                    String[] parts = text.split("\\s+", 2);
                                    String cmd = parts[0].toLowerCase();
                                    String[] cmdArgs = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
                                    
                                    log.warn("Processing message locally due to Kafka error: {} from user: {}", cmd, maskedChatId);
                                    
                                    userService.checkUserAuthorization(cmd, chatId)
                                        .flatMap(isAuthorized -> {
                                            if (!isAuthorized) {
                                                return sendResponseAsync(chatId, userService.getAuthorizationErrorMessage(cmd));
                                            }
                                            return botCommand.processCommand(cmd, cmdArgs, chatId);
                                        })
                                        .subscribe();
                                }
                            }
                        }
                    },
                    error -> log.error("Error checking user status: {}", error.getMessage())
                );
        }
    }


    /**
     * Обрабатывает команду.
     * Извлекает команду и аргументы, передает их в BotCommand для обработки.
     *
     * @param command Команда
     * @param args Аргументы команды
     * @param chatId ID чата
     * @return Mono<Void>
     */
    private Mono<Void> processCommand(String command, String[] args, String chatId) {
        // Обработка команды /start
        if ("/start".equals(command)) {
            return userService.getUserByChatId(chatId)
                .flatMap(user -> {
                    // Проверка флага должна учитывать, что это может быть первый запуск
                    // Пользователь мог быть создан, но не активирован
                    if (user.isHasStarted()) {
                        // Пользователь действительно уже начал работу с ботом - это повторный /start
                        log.info("User already started, sending welcome message again for chatId: {}", chatId);
                        // Вместо сообщения "Вы уже начали работу", отправляем обычное приветствие
                        return Mono.just(botCommand.handlerStart(String.join(" ", args)));
                    }
                    log.info("Setting hasStarted=true in processCommand for chatId: {}", chatId);
                    user.setHasStarted(true);
                    return userService.save(user)
                        .then(Mono.just(botCommand.handlerStart(String.join(" ", args))));
                })
                .switchIfEmpty(Mono.fromCallable(() -> {
                    log.info("Creating new user in processCommand for chatId: {}", chatId);
                    User newUser = new User(chatId);
                    newUser.setHasStarted(true);
                    return newUser;
                }).flatMap(user -> userService.save(user)
                    .then(Mono.just(botCommand.handlerStart(String.join(" ", args))))
                ))
                .flatMap(response -> sendResponseAsync(chatId, response))
                .then();
        }
        
        // Обработка остальных команд
        return botCommand.processCommand(command, args, chatId);
    }

    /**
     * Отправляет ответ пользователю.
     * Форматирует сообщение и отправляет его через Telegram API.
     *
     * @param chatId ID чата
     * @param text Текст сообщения
     * @return Mono<Void>
     */
    public Mono<Void> sendResponseAsync(String chatId, String text) {
        String maskedChatId = ChatIdMasker.maskChatId(chatId);
        logger.info("[SEND] Sending response to user: {} | text: {}", maskedChatId, text);
        return Mono.fromRunnable(() -> {
            try {
                kafkaProducer.sendOutgoingMessageAsync(chatId, text);
                logger.info("[SEND] Message sent to Kafka successfully for user: {}", maskedChatId);
            } catch (Exception e) {
                logger.error("[SEND] Error sending message to Kafka for user {}: {}", maskedChatId, e.getMessage(), e);
            }
        });
    }

    public Mono<String> processKafkaMessage(String jsonMessage) {
        try {
            String text = extractMessageFromJson(jsonMessage);
            String chatId = extractChatIdFromJson(jsonMessage);
            String maskedChatId = ChatIdMasker.maskChatId(chatId);
            
            if (text == null || chatId == null) {
                logger.error("Failed to extract message text or chatId from JSON");
                return Mono.just("Error: Invalid message format");
            }
            
            logger.info("Processing Kafka message from user: {}", maskedChatId);
            
            boolean isStartCommand = "/start".equals(text);
            
            // Для /start - обработка с учетом того, что юзер уже мог быть создан в onUpdateReceived
            if (isStartCommand) {
                return userService.getUserByChatId(chatId)
                    .flatMap(user -> {
                        // Проверяем был ли пользователь уже активирован
                        if (!user.isHasStarted()) {
                            logger.info("User exists but not started, activating user: {}", maskedChatId);
                            user.setHasStarted(true);
                            return userService.save(user)
                                .then(parseAndProcessCommand(text, chatId))
                                .then(Mono.just("Обработана команда /start - пользователь активирован"));
                        }
                        
                        logger.info("User already exists and is active, processing /start command: {}", maskedChatId);
                        // Если пользователь уже активирован, просто обрабатываем команду
                        return parseAndProcessCommand(text, chatId)
                            .then(Mono.just("Обработана команда /start"));
                    })
                    .switchIfEmpty(Mono.fromCallable(() -> {
                        logger.info("[START] User not found in Kafka processor, creating new: {}", maskedChatId);
                        User newUser = new User(chatId);
                        newUser.setHasStarted(true);
                        return newUser;
                    }).flatMap(newUser -> userService.save(newUser)
                        .doOnSuccess(u -> log.info("[START] New user saved successfully: {}", maskedChatId))
                        .doOnError(e -> log.error("[START] Error saving new user: {}: {}", maskedChatId, e.getMessage(), e))
                        .flatMap(u -> parseAndProcessCommand(text, chatId)
                            .doOnSuccess(v -> log.info("[START] parseAndProcessCommand completed for new user: {}", maskedChatId))
                            .doOnError(e -> log.error("[START] Error in parseAndProcessCommand for new user: {}: {}", maskedChatId, e.getMessage(), e))
                            .thenReturn("Обработана команда /start - создан новый пользователь")
                        )
                        .doOnSuccess(msg -> log.info("[START] Success message for new user: {}: {}", maskedChatId, msg))
                        .doOnError(e -> log.error("[START] Error after full new user flow: {}: {}", maskedChatId, e.getMessage(), e))
                        .onErrorReturn("Ошибка при создании нового пользователя")
                    ));
            }
            
            // Для других команд проверяем статус пользователя
            return userService.getUserByChatId(chatId)
                .<String>flatMap(user -> {
                    if (!isStartCommand && !user.isHasStarted()) {
                        return sendResponseAsync(chatId, "❌ Пожалуйста, начните работу с ботом командой /start")
                            .then(Mono.just("❌ Пожалуйста, начните работу с ботом командой /start"));
                    }
                    
                    if (text.startsWith("/")) {
                        return parseAndProcessCommand(text, chatId)
                            .then(Mono.just("Команда обработана"));
                    }
                    
                    return Mono.just(botCommand.handlerQ())
                        .flatMap(response -> sendResponseAsync(chatId, response)
                            .then(Mono.just(response)));
                })
                .switchIfEmpty(
                    sendResponseAsync(chatId, "❌ Пожалуйста, начните работу с ботом командой /start")
                        .thenReturn("❌ Пожалуйста, начните работу с ботом командой /start")
                );
        } catch (Exception e) {
            logger.error("Error processing Kafka message: {}", e.getMessage());
            return Mono.just("Произошла ошибка при обработке сообщения.");
        }
    }
    
    private String extractMessageFromJson(String messageJson) {
        try {
            int start = messageJson.indexOf("\"message\":\"") + 11;
            if (start < 11) return null;
            int end = messageJson.indexOf("\"", start);
            return messageJson.substring(start, end);
        } catch (Exception e) {
            logger.error("Error extracting message from JSON: {}", messageJson, e);
            return null;
        }
    }
    
    private String extractChatIdFromJson(String messageJson) {
        try {
            // Сначала пытаемся найти chatId в кавычках
            int start = messageJson.indexOf("\"chatId\":\"") + 10;
            if (start < 10) {
                // Если не найдено в кавычках, пробуем без кавычек
                start = messageJson.indexOf("\"chatId\":") + 9;
                if (start < 9) {
                    logger.error("Не удалось найти chatId в сообщении: {}", messageJson);
                    return null;
                }
                // Находим конец числа
                int end = messageJson.indexOf(",", start);
                if (end == -1) {
                    end = messageJson.indexOf("}", start);
                }
                if (end == -1) {
                    logger.error("Не удалось найти конец chatId в сообщении: {}", messageJson);
                    return null;
                }
                return messageJson.substring(start, end).trim();
            }
            // Если найдено в кавычках, находим закрывающую кавычку
            int end = messageJson.indexOf("\"", start);
            if (end == -1) {
                logger.error("Не удалось найти конец chatId в кавычках: {}", messageJson);
                return null;
            }
            return messageJson.substring(start, end);
        } catch (Exception e) {
            logger.error("Ошибка при извлечении chatId: {}", e.getMessage());
            return null;
        }
    }


    private Mono<Void> parseAndProcessCommand(String text, String chatId) {
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];
        
        return processCommand(command, args, chatId);
    }
}
