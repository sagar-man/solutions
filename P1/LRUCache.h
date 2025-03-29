#ifndef LRU_CACHE_H
#define LRU_CACHE_H

#include <iostream>
#include <list>
#include <unordered_map>
#include <ctime>
#include <sys/stat.h>
#include <memory>

struct FileInfo {
    std::string path;
    time_t opened_at;
    struct stat metadata;
};

class LRUCache {
public:
    explicit LRUCache(size_t capacity, time_t expiry_time);
    void put(const std::string &filepath);
    std::shared_ptr<FileInfo> get(const std::string &filepath);
    void remove(const std::string &filepath);
    void cleanup();
    void display() const;

private:
    using ListIterator = std::list<std::pair<std::string, FileInfo>>::iterator;
    void moveToFront(ListIterator it);
    
    size_t capacity;
    time_t expiry_time;
    std::list<std::pair<std::string, FileInfo>> cache_list;
    std::unordered_map<std::string, ListIterator> cache_map;
};

#endif