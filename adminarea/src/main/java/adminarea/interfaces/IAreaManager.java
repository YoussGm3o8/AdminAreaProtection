package adminarea.interfaces;

import java.util.List;

import adminarea.area.Area;

public interface IAreaManager {
    void addArea(Area area);
    void removeArea(Area area);
    void updateArea(Area area);
    Area getArea(String name);
    List<Area> getAllAreas();
    boolean hasArea(String name);
}
