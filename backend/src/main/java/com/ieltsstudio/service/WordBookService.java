package com.ieltsstudio.service;

import com.ieltsstudio.entity.WordBook;
import com.ieltsstudio.entity.WordEntry;
import com.ieltsstudio.mapper.WordBookMapper;
import com.ieltsstudio.mapper.WordEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WordBookService {

    private final WordBookMapper wordBookMapper;
    private final WordEntryMapper wordEntryMapper;
    private final AsyncWordService asyncWordService;
    private final CacheManager cacheManager;

    /** Ensure the user has a default 生词本; create one if absent. */
    public WordBook ensureDefaultBook(Long userId) {
        WordBook existing = wordBookMapper.findDefaultBook(userId);
        if (existing != null) return existing;
        WordBook book = new WordBook();
        book.setUserId(userId);
        book.setName("生词本");
        book.setDescription("我的个人生词本");
        book.setIsDefault(1);
        book.setWordCount(0);
        wordBookMapper.insert(book);
        return book;
    }

    @Cacheable(cacheNames = "books", key = "#userId")
    public List<WordBook> getUserBooks(Long userId) {
        return wordBookMapper.findByUserId(userId);
    }

    public WordBook createBook(Long userId, String name, String description) {
        WordBook book = new WordBook();
        book.setUserId(userId);
        book.setName(name);
        book.setDescription(description);
        book.setIsDefault(0);
        book.setWordCount(0);
        wordBookMapper.insert(book);
        evictBooksCache(userId);
        return book;
    }

    public boolean deleteBook(Long userId, Long bookId) {
        WordBook book = wordBookMapper.selectById(bookId);
        if (book == null || !book.getUserId().equals(userId)) return false;
        if (book.getIsDefault() != null && book.getIsDefault() == 1) return false; // cannot delete default
        wordBookMapper.deleteById(bookId);
        evictEntriesCache(userId, bookId);
        evictBooksCache(userId);
        return true;
    }

    @Cacheable(cacheNames = "entries", key = "#userId + ':' + #bookId", unless = "#result == null || #result.isEmpty()")
    public List<WordEntry> getEntries(Long userId, Long bookId) {
        WordBook book = wordBookMapper.selectById(bookId);
        if (book == null || !book.getUserId().equals(userId)) return Collections.emptyList();
        return wordEntryMapper.findByBookId(bookId);
    }

    public boolean existsForUser(Long userId, Long bookId) {
        WordBook book = wordBookMapper.selectById(bookId);
        return book != null && book.getUserId().equals(userId);
    }

    public boolean deleteEntry(Long userId, Long entryId) {
        WordEntry entry = wordEntryMapper.selectById(entryId);
        if (entry == null || !entry.getUserId().equals(userId)) return false;
        wordEntryMapper.deleteById(entryId);
        WordBook book = wordBookMapper.selectById(entry.getBookId());
        if (book != null) {
            book.setWordCount(wordEntryMapper.countByBookId(entry.getBookId()));
            wordBookMapper.updateById(book);
        }
        evictEntriesCache(userId, entry.getBookId());
        evictBooksCache(userId);
        return true;
    }

    /**
     * Kick off async word processing — returns immediately with status "processing".
     */
    public Map<String, Object> startUpload(Long userId, Long bookId, MultipartFile file) throws Exception {
        WordBook book = wordBookMapper.selectById(bookId);
        if (book == null || !book.getUserId().equals(userId)) {
            throw new IllegalArgumentException("词书不存在");
        }
        // Read bytes before async (MultipartFile stream closes after request)
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename();
        // Kick off async — returns immediately
        asyncWordService.processWordFile(userId, bookId, bytes, filename);
        Map<String, Object> result = new HashMap<>();
        result.put("status", "processing");
        result.put("bookId", bookId);
        return result;
    }

    public WordEntry updateEntry(Long userId, Long entryId, String meaning, String example, String exampleTranslation, String rootMemory) {
        WordEntry entry = wordEntryMapper.selectById(entryId);
        if (entry == null || !entry.getUserId().equals(userId)) return null;
        if (meaning != null) entry.setMeaning(meaning.trim());
        if (example != null) entry.setExample(example.trim());
        if (exampleTranslation != null) entry.setExampleTranslation(exampleTranslation.trim());
        if (rootMemory != null) entry.setRootMemory(rootMemory.trim());
        wordEntryMapper.updateById(entry);
        evictEntriesCache(userId, entry.getBookId());
        evictBooksCache(userId);
        return entry;
    }

    /** Copy a word entry from any book into the user's default 生词本. */
    public WordEntry copyToDefaultBook(Long userId, Long entryId) {
        WordEntry src = wordEntryMapper.selectById(entryId);
        if (src == null || !src.getUserId().equals(userId)) return null;
        WordBook defaultBook = ensureDefaultBook(userId);
        // dedupe: if same word already exists in default book, return it
        Long existId = wordEntryMapper.findIdByBookIdAndWord(defaultBook.getId(), src.getWord());
        if (existId != null) {
            return wordEntryMapper.selectById(existId);
        }
        WordEntry copy = new WordEntry();
        copy.setBookId(defaultBook.getId());
        copy.setUserId(userId);
        copy.setWord(src.getWord());
        copy.setPhonetic(src.getPhonetic());
        copy.setPos(src.getPos());
        copy.setPosType(src.getPosType());
        copy.setMeaning(src.getMeaning());
        copy.setExample(src.getExample());
        copy.setExampleTranslation(src.getExampleTranslation());
        copy.setRootMemory(src.getRootMemory());
        wordEntryMapper.insert(copy);
        WordBook book = wordBookMapper.selectById(defaultBook.getId());
        if (book != null) {
            book.setWordCount(wordEntryMapper.countByBookId(defaultBook.getId()));
            wordBookMapper.updateById(book);
        }
        evictEntriesCache(userId, defaultBook.getId());
        evictBooksCache(userId);
        return copy;
    }

    /** Add a word by data (used for builtin/client-side words that have no DB entry). */
    public WordEntry addWordToDefaultBook(Long userId, Map<String, String> data) {
        WordBook defaultBook = ensureDefaultBook(userId);
        String word = data.getOrDefault("word", "").trim();
        // dedupe before building entry
        if (!word.isEmpty()) {
            Long existId = wordEntryMapper.findIdByBookIdAndWord(defaultBook.getId(), word);
            if (existId != null) return wordEntryMapper.selectById(existId);
        }
        WordEntry entry = new WordEntry();
        entry.setBookId(defaultBook.getId());
        entry.setUserId(userId);
        entry.setWord(word);
        entry.setPhonetic(data.get("phonetic"));
        entry.setPos(data.get("pos"));
        entry.setPosType(data.get("posType"));
        entry.setMeaning(data.getOrDefault("meaning", "").trim());
        entry.setExample(data.get("example"));
        entry.setExampleTranslation(data.get("exampleTranslation"));
        entry.setRootMemory(data.get("rootMemory"));
        if (entry.getWord().isEmpty() || entry.getMeaning().isEmpty()) return null;
        wordEntryMapper.insert(entry);
        WordBook book = wordBookMapper.selectById(defaultBook.getId());
        if (book != null) {
            book.setWordCount(wordEntryMapper.countByBookId(defaultBook.getId()));
            wordBookMapper.updateById(book);
        }
        evictEntriesCache(userId, defaultBook.getId());
        evictBooksCache(userId);
        return entry;
    }

    public WordBook getBook(Long userId, Long bookId) {
        WordBook book = wordBookMapper.selectById(bookId);
        if (book == null || !book.getUserId().equals(userId)) return null;
        book.setWordCount(wordEntryMapper.countByBookId(bookId));
        return book;
    }

    private void evictBooksCache(Long userId) {
        Cache cache = cacheManager.getCache("books");
        if (cache != null) cache.evict(userId);
    }

    private void evictEntriesCache(Long userId, Long bookId) {
        Cache cache = cacheManager.getCache("entries");
        if (cache != null) cache.evict(userId + ":" + bookId);
    }
}
