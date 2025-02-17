// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

/**
 * This is the application class of the fbench program. It controls
 * the operation of the test clients and collects overall results.
 * The functionallity of the Main method is split into several helper
 * methods for more clarity in the source.
 **/
class FBench
{
private:
    vespalib::CryptoEngine::SP _crypto_engine;
    std::vector<Client::UP> _clients;
    int                 _numClients;
    int                 _ignoreCount;
    int                 _cycle;
    std::vector<std::string> _hostnames;
    std::vector<int>    _ports;
    char               *_filenamePattern;
    char               *_outputPattern;
    int                 _byteLimit;
    int                 _restartLimit;
    int                 _maxLineSize;
    bool                _keepAlive;
    bool                _usePostMode;
    bool                _headerBenchmarkdataCoverage;
    int                 _seconds;
    std::vector<uint64_t> _queryfileOffset;
    int                 _numberOfQueries;
    bool                _singleQueryFile;
    std::string         _queryStringToAppend;
    std::string         _extraHeaders;
    std::string         _authority;

    bool init_crypto_engine(const std::string &ca_certs_file_name,
                            const std::string &cert_chain_file_name,
                            const std::string &private_key_file_name,
                            bool allow_default_tls);

    void InitBenchmark(int numClients, int ignoreCount, int cycle,
                       const char *filenamePattern, const char *outputPattern,
                       int byteLimit, int restartLimit, int maxLineSize,
                       bool keepAlive, bool headerBenchmarkdataCoverage, int seconds,
                       bool singleQueryFile, const std::string & queryStringToAppend, const std::string & extraHeaders,
                       const std::string &authority, bool postMode);

    void CreateClients();
    void StartClients();
    void StopClients();
    bool ClientsDone();
    void PrintSummary();

    FBench(const FBench &);
    FBench &operator=(const FBench &);

public:
    FBench();
    ~FBench();

    /**
     * Exit
     **/
    void Exit();

    /**
     * Usage statement.
     */
    void Usage();

    /**
     * Application entry point.
     **/
    int Main(int argc, char *argv[]);
};

int main(int argc, char** argv);
