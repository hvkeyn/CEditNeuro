package com.hvkeyn.ceditneuro.shizuku;

interface IShellService {
    void destroy() = 16777114;

    String exec(String command, String cwd, int timeoutSeconds) = 1;
}
