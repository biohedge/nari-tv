package dev.naritv;

interface IRouteService {
    String ping();
    String addGame(int card);
    String switchGame(int card);
    String restore(int card);
    void destroy();
}
