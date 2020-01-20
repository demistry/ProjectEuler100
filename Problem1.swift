


/*
QUESTION: If we list all the natural numbers below 10 that are multiples of 3 or 5, we get 3, 5, 6 and 9. The sum of these multiples is 23.

Find the sum of all the multiples of 3 or 5 below the provided parameter value number.
*/





func multiplesOf3or5(number : Int)->Int{
    var sum = 0
    for num in 0..<number{
        if num % 3 == 0 || num % 5 == 0{
            sum += num
        }
    }
    return sum
}

print("Sum : \(multiplesOf3or5(number: 8456))")
