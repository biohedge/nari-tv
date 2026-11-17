package dev.naritv;

interface IRouteService {
    String ping();
    String addGame(int card);
    String switchGame(int card);
    String refreshGame(int card);
    String restore(int card);
    String setAutoRepair(int card, boolean enabled);
    String getAutoStatus();
    void destroy();
}
