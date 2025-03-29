#include "LRUCache.h"

int main()
{
    LRUCache cache(3, 60); // Capacity of 3, expiry time of 60 seconds
    
    cache.put("/tmp/file1.txt");
    cache.put("/tmp/file2.txt");
    cache.put("/tmp/file3.txt");
    
    cache.display();
    
    std::cout << "Accessing /tmp/file1.txt" << std::endl;
    cache.get("/tmp/file1.txt");
    cache.display();
    
    std::cout << "Adding new file /tmp/file4.txt" << std::endl;
    cache.put("/tmp/file4.txt");
    cache.display();
    
    std::cout << "Cleaning up old entries..." << std::endl;
    cache.cleanup();
    cache.display();
    
    return 0;
}
