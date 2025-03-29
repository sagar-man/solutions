
#include "LRUCache.h"

LRUCache::LRUCache(size_t capacity, time_t expiry_time) 
    : capacity(capacity), expiry_time(expiry_time) {}

void LRUCache::put(const std::string &filepath)
{
    struct stat file_stat{};
    if (stat(filepath.c_str(), &file_stat) != 0)
    {
        std::cerr << "Error: Cannot retrieve file info for " << filepath << std::endl;
        return;
    }

    auto it = cache_map.find(filepath);
    if (it != cache_map.end())
    {
        moveToFront(it->second);
        return;
    }

    if (cache_list.size() >= capacity)
    {
        auto last = cache_list.back();
        cache_map.erase(last.first);
        cache_list.pop_back();
    }

    cache_list.emplace_front(filepath, FileInfo{filepath, time(nullptr), file_stat});
    cache_map[filepath] = cache_list.begin();
}

std::shared_ptr<FileInfo> LRUCache::get(const std::string &filepath)
{
    auto it = cache_map.find(filepath);
    if (it == cache_map.end())
    {
        return nullptr;
    }
    moveToFront(it->second);
    return std::make_shared<FileInfo>(it->second->second);
}

void LRUCache::remove(const std::string &filepath)
{
    auto it = cache_map.find(filepath);
    if (it == cache_map.end())
    {
        return;
    }
    cache_list.erase(it->second);
    cache_map.erase(it);
}

void LRUCache::cleanup()
{
    time_t now = time(nullptr);
    for (auto it = cache_list.begin(); it != cache_list.end();)
    {
        if (now - it->second.opened_at > expiry_time)
        {
            cache_map.erase(it->first);
            it = cache_list.erase(it);
        }
        else
        {
            ++it;
        }
    }
}

void LRUCache::display() const
{
    for (const auto &entry : cache_list)
    {
        std::cout << "File: " << entry.second.path << " Opened At: " << entry.second.opened_at << std::endl;
    }
}

void LRUCache::moveToFront(ListIterator it)
{
    cache_list.splice(cache_list.begin(), cache_list, it);
}