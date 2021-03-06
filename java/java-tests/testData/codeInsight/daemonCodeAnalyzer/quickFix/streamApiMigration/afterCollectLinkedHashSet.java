// "Replace with collect" "true"
import java.util.*;
import java.util.stream.Collectors;

public class Collect {
  class Person {
    String getName() {
      return "";
    }
  }

  void collectNames(List<Person> persons){
    Set<String> names = persons.stream().map(person::getName).collect(Collectors.toCollection(() -> new LinkedHashSet<>()));
  }
}
