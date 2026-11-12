import Greeter

let name = CommandLine.arguments.dropFirst().first ?? "world"
print(Greeter(name: name).greeting())
