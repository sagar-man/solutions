#include <iostream>
#include <fstream>
#include <vector>
#include <unordered_set>
#include <mutex>
#include <thread>
#include <shared_mutex>
#include <sstream>

std::unordered_set<int> global_unique_numbers;
std::shared_mutex global_mutex;

void process_section(const std::string &filename, std::streampos start, std::streampos end)
{
    std::ifstream file(filename);
    if (!file)
    {
        std::cerr << "Error opening file." << std::endl;
        return;
    }

    file.seekg(start);
    std::unordered_set<int> local_unique_numbers;
    std::string line;

    while (file.tellg() < end && std::getline(file, line))
    {
        std::istringstream iss(line);
        int number;
        while (iss >> number)
        {
            local_unique_numbers.insert(number);
        }
    }

    std::unique_lock lock(global_mutex);
    global_unique_numbers.insert(local_unique_numbers.begin(), local_unique_numbers.end());
}

void process_file(const std::string &filename, int num_threads)
{
    std::ifstream file(filename, std::ios::ate | std::ios::binary);
    if (!file)
    {
        std::cerr << "Error opening file." << std::endl;
        return;
    }

    std::streampos file_size = file.tellg();
    file.close();

    std::vector<std::thread> threads;
    std::streampos chunk_size = file_size / num_threads;
    
    for (int i = 0; i < num_threads; ++i)
    {
        std::streampos start = i * chunk_size;
        std::streampos end = (i == num_threads - 1) ? file_size : start + chunk_size;
        threads.emplace_back(process_section, std::cref(filename), start, end);
    }
    
    for (auto &t : threads)
    {
        t.join();
    }
}

int main()
{
    constexpr int num_threads = std::thread::hardware_concurrency();
    std::string filename = "numbers.txt";
    
    process_file(filename, num_threads);
    
    std::cout << "Unique numbers found:" << std::endl;
    for (int num : global_unique_numbers)
    {
        std::cout << num << " ";
    }
    std::cout << std::endl;
    
    return 0;
}
